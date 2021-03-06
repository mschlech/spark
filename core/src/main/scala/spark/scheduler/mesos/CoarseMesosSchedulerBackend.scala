package spark.scheduler.mesos

import com.google.protobuf.ByteString

import org.apache.mesos.{Scheduler => MScheduler}
import org.apache.mesos._
import org.apache.mesos.Protos.{TaskInfo => MesosTaskInfo, TaskState => MesosTaskState, _}

import spark.{SparkException, Utils, Logging, SparkContext}
import scala.collection.mutable.{ArrayBuffer, HashMap, HashSet}
import scala.collection.JavaConversions._
import java.io.File
import spark.scheduler.cluster._
import java.util.{ArrayList => JArrayList, List => JList}
import java.util.Collections
import spark.TaskState

/**
 * A SchedulerBackend that runs tasks on Mesos, but uses "coarse-grained" tasks, where it holds
 * onto each Mesos node for the duration of the Spark job instead of relinquishing cores whenever
 * a task is done. It launches Spark tasks within the coarse-grained Mesos tasks using the
 * StandaloneBackend mechanism. This class is useful for lower and more predictable latency.
 *
 * Unfortunately this has a bit of duplication from MesosSchedulerBackend, but it seems hard to
 * remove this.
 */
private[spark] class CoarseMesosSchedulerBackend(
    scheduler: ClusterScheduler,
    sc: SparkContext,
    master: String,
    frameworkName: String)
  extends StandaloneSchedulerBackend(scheduler, sc.env.actorSystem)
  with MScheduler
  with Logging {

  val MAX_SLAVE_FAILURES = 2     // Blacklist a slave after this many failures

  // Memory used by each executor (in megabytes)
  val executorMemory = {
    if (System.getenv("SPARK_MEM") != null) {
      Utils.memoryStringToMb(System.getenv("SPARK_MEM"))
      // TODO: Might need to add some extra memory for the non-heap parts of the JVM
    } else {
      512
    }
  }

  // Lock used to wait for scheduler to be registered
  var isRegistered = false
  val registeredLock = new Object()

  // Driver for talking to Mesos
  var driver: SchedulerDriver = null

  // Maximum number of cores to acquire (TODO: we'll need more flexible controls here)
  val maxCores = System.getProperty("spark.cores.max", Int.MaxValue.toString).toInt

  // Cores we have acquired with each Mesos task ID
  val coresByTaskId = new HashMap[Int, Int]
  var totalCoresAcquired = 0

  val slaveIdsWithExecutors = new HashSet[String]

  val taskIdToSlaveId = new HashMap[Int, String]
  val failuresBySlaveId = new HashMap[String, Int] // How many times tasks on each slave failed

  val sparkHome = sc.getSparkHome() match {
    case Some(path) =>
      path
    case None =>
      throw new SparkException("Spark home is not set; set it through the spark.home system " +
        "property, the SPARK_HOME environment variable or the SparkContext constructor")
  }

  val extraCoresPerSlave = System.getProperty("spark.mesos.extra.cores", "0").toInt

  var nextMesosTaskId = 0

  def newMesosTaskId(): Int = {
    val id = nextMesosTaskId
    nextMesosTaskId += 1
    id
  }

  override def start() {
    super.start()

    synchronized {
      new Thread("CoarseMesosSchedulerBackend driver") {
        setDaemon(true)
        override def run() {
          val scheduler = CoarseMesosSchedulerBackend.this
          val fwInfo = FrameworkInfo.newBuilder().setUser("").setName(frameworkName).build()
          driver = new MesosSchedulerDriver(scheduler, fwInfo, master)
          try { {
            val ret = driver.run()
            logInfo("driver.run() returned with code " + ret)
          }
          } catch {
            case e: Exception => logError("driver.run() failed", e)
          }
        }
      }.start()

      waitForRegister()
    }
  }

  def createCommand(offer: Offer, numCores: Int): CommandInfo = {
    val runScript = new File(sparkHome, "run").getCanonicalPath
    val masterUrl = "akka://spark@%s:%s/user/%s".format(
      System.getProperty("spark.master.host"), System.getProperty("spark.master.port"),
      StandaloneSchedulerBackend.ACTOR_NAME)
    val command = "\"%s\" spark.executor.StandaloneExecutorBackend %s %s %s %d".format(
      runScript, masterUrl, offer.getSlaveId.getValue, offer.getHostname, numCores)
    val environment = Environment.newBuilder()
    sc.executorEnvs.foreach { case (key, value) =>
      environment.addVariables(Environment.Variable.newBuilder()
        .setName(key)
        .setValue(value)
        .build())
    }
    return CommandInfo.newBuilder().setValue(command).setEnvironment(environment).build()
  }

  override def offerRescinded(d: SchedulerDriver, o: OfferID) {}

  override def registered(d: SchedulerDriver, frameworkId: FrameworkID, masterInfo: MasterInfo) {
    logInfo("Registered as framework ID " + frameworkId.getValue)
    registeredLock.synchronized {
      isRegistered = true
      registeredLock.notifyAll()
    }
  }

  def waitForRegister() {
    registeredLock.synchronized {
      while (!isRegistered) {
        registeredLock.wait()
      }
    }
  }

  override def disconnected(d: SchedulerDriver) {}

  override def reregistered(d: SchedulerDriver, masterInfo: MasterInfo) {}

  /**
   * Method called by Mesos to offer resources on slaves. We respond by launching an executor,
   * unless we've already launched more than we wanted to.
   */
  override def resourceOffers(d: SchedulerDriver, offers: JList[Offer]) {
    synchronized {
      val filters = Filters.newBuilder().setRefuseSeconds(-1).build()

      for (offer <- offers) {
        val slaveId = offer.getSlaveId.toString
        val mem = getResource(offer.getResourcesList, "mem")
        val cpus = getResource(offer.getResourcesList, "cpus").toInt
        if (totalCoresAcquired < maxCores && mem >= executorMemory && cpus >= 1 &&
            failuresBySlaveId.getOrElse(slaveId, 0) < MAX_SLAVE_FAILURES &&
            !slaveIdsWithExecutors.contains(slaveId)) {
          // Launch an executor on the slave
          val cpusToUse = math.min(cpus, maxCores - totalCoresAcquired)
          val taskId = newMesosTaskId()
          taskIdToSlaveId(taskId) = slaveId
          slaveIdsWithExecutors += slaveId
          coresByTaskId(taskId) = cpusToUse
          val task = MesosTaskInfo.newBuilder()
            .setTaskId(TaskID.newBuilder().setValue(taskId.toString).build())
            .setSlaveId(offer.getSlaveId)
            .setCommand(createCommand(offer, cpusToUse + extraCoresPerSlave))
            .setName("Task " + taskId)
            .addResources(createResource("cpus", cpusToUse))
            .addResources(createResource("mem", executorMemory))
            .build()
          d.launchTasks(offer.getId, Collections.singletonList(task), filters)
        } else {
          // Filter it out
          d.launchTasks(offer.getId, Collections.emptyList[MesosTaskInfo](), filters)
        }
      }
    }
  }

  /** Helper function to pull out a resource from a Mesos Resources protobuf */
  def getResource(res: JList[Resource], name: String): Double = {
    for (r <- res if r.getName == name) {
      return r.getScalar.getValue
    }
    // If we reached here, no resource with the required name was present
    throw new IllegalArgumentException("No resource called " + name + " in " + res)
  }

  /** Build a Mesos resource protobuf object */
  def createResource(resourceName: String, quantity: Double): Protos.Resource = {
    Resource.newBuilder()
      .setName(resourceName)
      .setType(Value.Type.SCALAR)
      .setScalar(Value.Scalar.newBuilder().setValue(quantity).build())
      .build()
  }

  /** Check whether a Mesos task state represents a finished task */
  def isFinished(state: MesosTaskState) = {
    state == MesosTaskState.TASK_FINISHED ||
      state == MesosTaskState.TASK_FAILED ||
      state == MesosTaskState.TASK_KILLED ||
      state == MesosTaskState.TASK_LOST
  }

  override def statusUpdate(d: SchedulerDriver, status: TaskStatus) {
    val taskId = status.getTaskId.getValue.toInt
    val state = status.getState
    logInfo("Mesos task " + taskId + " is now " + state)
    synchronized {
      if (isFinished(state)) {
        val slaveId = taskIdToSlaveId(taskId)
        slaveIdsWithExecutors -= slaveId
        taskIdToSlaveId -= taskId
        // Remove the cores we have remembered for this task, if it's in the hashmap
        for (cores <- coresByTaskId.get(taskId)) {
          totalCoresAcquired -= cores
          coresByTaskId -= taskId
        }
        // If it was a failure, mark the slave as failed for blacklisting purposes
        if (state == MesosTaskState.TASK_FAILED || state == MesosTaskState.TASK_LOST) {
          failuresBySlaveId(slaveId) = failuresBySlaveId.getOrElse(slaveId, 0) + 1
          if (failuresBySlaveId(slaveId) >= MAX_SLAVE_FAILURES) {
            logInfo("Blacklisting Mesos slave " + slaveId + " due to too many failures; " +
                "is Spark installed on it?")
          }
        }
        driver.reviveOffers() // In case we'd rejected everything before but have now lost a node
      }
    }
  }

  override def error(d: SchedulerDriver, message: String) {
    logError("Mesos error: " + message)
    scheduler.error(message)
  }

  override def stop() {
    super.stop()
    if (driver != null) {
      driver.stop()
    }
  }

  override def frameworkMessage(d: SchedulerDriver, e: ExecutorID, s: SlaveID, b: Array[Byte]) {}

  override def slaveLost(d: SchedulerDriver, slaveId: SlaveID) {
    logInfo("Mesos slave lost: " + slaveId.getValue)
    synchronized {
      slaveIdsWithExecutors -= slaveId.getValue
    }
  }

  override def executorLost(d: SchedulerDriver, e: ExecutorID, s: SlaveID, status: Int) {
    logInfo("Executor lost: %s, marking slave %s as lost".format(e.getValue, s.getValue))
    slaveLost(d, s)
  }
}
