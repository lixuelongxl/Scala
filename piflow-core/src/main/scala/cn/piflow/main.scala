package cn.piflow

import java.io.IOException
import java.net.URI
import java.util.concurrent.{CountDownLatch, TimeUnit}

import cn.piflow.util.PropertyUtil
import cn.piflow.util.{HadoopFileUtil, IdGenerator, Logging, PropertyUtil}
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.FileSystem
import org.apache.hadoop.fs.Path
import org.apache.spark.sql._

import scala.collection.mutable.{ArrayBuffer, Map => MMap}

trait JobInputStream {
  def isEmpty(): Boolean;

  def read(): DataFrame;

  def ports(): Seq[String];

  def read(inport: String): DataFrame;
}

trait JobOutputStream {
  def makeCheckPoint(pec: JobContext): Unit;

  def loadCheckPoint(pec: JobContext, path : String) : Unit;

  def write(data: DataFrame);

  def write(bundle: String, data: DataFrame);

  def sendError();
}

trait StopJob {
  def jid(): String;

  def getStopName(): String;

  def getStop(): Stop;
}

trait JobContext extends Context {
  def getStopJob(): StopJob;

  def getInputStream(): JobInputStream;

  def getOutputStream(): JobOutputStream;

  def getProcessContext(): ProcessContext;
}

trait Stop {
  def initialize(ctx: ProcessContext): Unit;

  def perform(in: JobInputStream, out: JobOutputStream, pec: JobContext): Unit;
}

trait Flow {
  def getStopNames(): Seq[String];

  def hasCheckPoint(processName: String): Boolean;

  def getStop(name: String): Stop;

  def analyze(): AnalyzedFlowGraph;

  def show(): Unit;

  def getFlowName(): String;

  def setFlowName(flowName : String): Unit;

  def getCheckpointParentProcessId() : String;

  def setCheckpointParentProcessId(checkpointParentProcessId : String);
}

class FlowImpl extends Flow {
  var name = ""
  val edges = ArrayBuffer[Edge]();
  val stops = MMap[String, Stop]();
  val checkpoints = ArrayBuffer[String]();
  var checkpointParentProcessId = ""

  def addStop(name: String, process: Stop) = {
    stops(name) = process;
    this;
  };

  override def show(): Unit = {
    edges.foreach { arrow =>
      println(arrow.toString());
    }
  }

  def addCheckPoint(processName: String): Unit = {
    checkpoints += processName;
  }

  override def hasCheckPoint(processName: String): Boolean = {
    checkpoints.contains(processName);
  }

  override def getStop(name: String) = stops(name);

  override def getStopNames(): Seq[String] = stops.map(_._1).toSeq;

  def addPath(path: Path): Flow = {
    edges ++= path.toEdges();
    this;
  }



  override def analyze(): AnalyzedFlowGraph =
    new AnalyzedFlowGraph() {
      val incomingEdges = MMap[String, ArrayBuffer[Edge]]();
      val outgoingEdges = MMap[String, ArrayBuffer[Edge]]();

      edges.foreach { edge =>
        incomingEdges.getOrElseUpdate(edge.stopTo, ArrayBuffer[Edge]()) += edge;
        outgoingEdges.getOrElseUpdate(edge.stopFrom, ArrayBuffer[Edge]()) += edge;
      }

      private def _visitProcess[T](flow: Flow, processName: String, op: (String, Map[Edge, T]) => T, visited: MMap[String, T]): T = {
        if (!visited.contains(processName)) {

          //TODO: need to check whether the checkpoint's data exist!!!!
          if(flow.hasCheckPoint(processName) && !flow.getCheckpointParentProcessId().equals("")){
            val ret = op(processName, null);
            visited(processName) = ret;
            return ret;
          }
          //executes dependent processes
          val inputs =
            if (incomingEdges.contains(processName)) {
              //all incoming edges
              val edges = incomingEdges(processName);
              edges.map { edge =>
                edge ->
                  _visitProcess(flow, edge.stopFrom, op, visited);
              }.toMap
            }
            else {
              Map[Edge, T]();
            }

          val ret = op(processName, inputs);
          visited(processName) = ret;
          ret;
        }
        else {
          visited(processName);
        }
      }

      override def visit[T](flow: Flow, op: (String, Map[Edge, T]) => T): Unit = {
        val ends = stops.keys.filterNot(outgoingEdges.contains(_));
        val visited = MMap[String, T]();
        ends.foreach {
          _visitProcess(flow, _, op, visited);
        }
      }
    }

  override def getFlowName(): String = {
    this.name
  }

  override def setFlowName(flowName : String): Unit = {
    this.name = flowName;
  }

  //get the processId
  override def getCheckpointParentProcessId() : String = {
    this.checkpointParentProcessId
  }

  override def setCheckpointParentProcessId(checkpointParentProcessId : String) = {
    this.checkpointParentProcessId = checkpointParentProcessId
  }
}

trait AnalyzedFlowGraph {
  def visit[T](flow: Flow, op: (String, Map[Edge, T]) => T): Unit;
}

trait Process {
  def pid(): String;

  def awaitTermination();

  def awaitTermination(timeout: Long, unit: TimeUnit);

  def getFlow(): Flow;

  def fork(child: Flow): Process;

  def stop(): Unit;
}

trait ProcessContext extends Context {
  def getFlow(): Flow;

  def getProcess(): Process;
}

class JobInputStreamImpl() extends JobInputStream {
  //only returns DataFrame on calling read()
  val inputs = MMap[String, () => DataFrame]();

  override def isEmpty(): Boolean = inputs.isEmpty;

  def attach(inputs: Map[Edge, JobOutputStreamImpl]) = {
    this.inputs ++= inputs.filter(x => x._2.contains(x._1.outport))
      .map(x => (x._1.inport, x._2.getDataFrame(x._1.outport)));
  };

  override def ports(): Seq[String] = {
    inputs.keySet.toSeq;
  }

  override def read(): DataFrame = {
    if (inputs.isEmpty)
      throw new NoInputAvailableException();

    read(inputs.head._1);
  };

  override def read(inport: String): DataFrame = {
    inputs(inport)();
  }
}

class JobOutputStreamImpl() extends JobOutputStream with Logging {
  private val defaultPort = "default"

  override def makeCheckPoint(pec: JobContext) {
    mapDataFrame.foreach(en => {
      val port = if(en._1.equals("")) defaultPort else en._1
      val path = pec.get("checkpoint.path").asInstanceOf[String].stripSuffix("/") + "/" + pec.getProcessContext().getProcess().pid() + "/" + pec.getStopJob().getStopName() + "/" + port;
      println("MakeCheckPoint Path: " + path)
      //val path = getCheckPointPath(pec)
      logger.debug(s"writing data on checkpoint: $path");

      en._2.apply().write.parquet(path);
      mapDataFrame(en._1) = () => {
        logger.debug(s"loading data from checkpoint: $path");
        pec.get[SparkSession].read.parquet(path)//default port?
      };
    })
  }

  //load the checkpoint by path and port
  override def loadCheckPoint(pec: JobContext, checkpointPath : String): Unit = {

    val ports = getCheckPointPorts(pec, checkpointPath)
    ports.foreach{ port => {

      val mdf = () => {
        val checkpointPortPath = checkpointPath + "/" + port
        logger.debug(s"loading data from checkpoint: $checkpointPortPath")
        println(s"loading data from checkpoint: $checkpointPortPath")
        pec.get[SparkSession].read.parquet(checkpointPortPath)
      };

      val newPort = if(port.equals(defaultPort)) "" else port
      mapDataFrame(newPort) = mdf

    }}
  }

  //get then checkpoint path
  private def getCheckPointPath(pec: JobContext) : String = {
    val pathStr = pec.get("checkpoint.path").asInstanceOf[String].stripSuffix("/") + "/" + pec.getProcessContext().getProcess().pid() + "/" + pec.getStopJob().getStopName();
    val conf:Configuration = new Configuration()
    try{
      val fs:FileSystem = FileSystem.get(URI.create(pathStr), conf)
      val path = new org.apache.hadoop.fs.Path(pathStr)
      if(fs.exists(path)){
        pathStr
      }else{
        ""
      }
    }catch{
      case ex:IOException =>{
        println(ex)
        ""
      }
      case _ => ""
    }
  }

  //get the checkpoint ports list
  private def getCheckPointPorts(pec: JobContext, checkpointPath : String) : List[String] = {
    HadoopFileUtil.getFileInHadoopPath(checkpointPath)
  }

  val mapDataFrame = MMap[String, () => DataFrame]();

  override def write(data: DataFrame): Unit = write("", data);

  override def sendError(): Unit = ???

  override def write(outport: String, data: DataFrame): Unit = {
    mapDataFrame(outport) = () => data;
  }

  def contains(port: String) = mapDataFrame.contains(port);

  def getDataFrame(port: String) = mapDataFrame(port);

  def showDataFrame() = {

      mapDataFrame.foreach(en => {
        val portName = if(en._1.equals("")) "default" else en._1
        println(portName + " port: ")
        en._2.apply().show(PropertyUtil.getPropertyValue("data.show").toInt)
      })
  }

}

class ProcessImpl(flow: Flow, runnerContext: Context, runner: Runner, parentProcess: Option[Process] = None)
  extends Process with Logging {

  val id = "process_" + IdGenerator.uuid() + "_" + IdGenerator.nextId[Process];
  val executionString = "" + id + parentProcess.map("(parent=" + _.toString + ")").getOrElse("");

  logger.debug(s"create process: $this, flow: $flow");
  flow.show();

  val process = this;
  val runnerListener = runner.getListener();
  val processContext = createContext(runnerContext);
  val latch = new CountDownLatch(1);
  var running = false;

  val workerThread = new Thread(new Runnable() {
    def perform() {
      //initialize all processes
      //initialize process context
      val jobs = MMap[String, StopJobImpl]();
      flow.getStopNames().foreach { stopName =>
        val stop = flow.getStop(stopName);
        stop.initialize(processContext);

        val pe = new StopJobImpl(stopName, stop, processContext);
        jobs(stopName) = pe;
        runnerListener.onJobInitialized(pe.getContext());
      }

      //TODO: judge streaming stop

      val analyzed = flow.analyze();
      val checkpointParentProcessId = flow.getCheckpointParentProcessId()
      analyzed.visit[JobOutputStreamImpl](flow,performStopByCheckpoint)

      /*if (checkpointParentProcessId == "" ){
        analyzed.visit[JobOutputStreamImpl](performStop)

      }else{
        analyzed.visit[JobOutputStreamImpl](performStopByCheckpoint)
      }*/

      //runs processes
      /*analyzed.visit[JobOutputStreamImpl]((stopName: String, inputs: Map[Edge, JobOutputStreamImpl]) => {
        val pe = jobs(stopName);
        var outputs: JobOutputStreamImpl = null;
        try {
          runnerListener.onJobStarted(pe.getContext());
          outputs = pe.perform(inputs);
          runnerListener.onJobCompleted(pe.getContext());

          //is a checkpoint?
          if (flow.hasCheckPoint(stopName)) {
            //store dataset
            outputs.makeCheckPoint(pe.getContext());
          }
        }
        catch {
          case e: Throwable =>
            runnerListener.onJobFailed(pe.getContext());
            throw e;
        }

        outputs;
      }
      );*/

      def performStop(stopName: String, inputs: Map[Edge, JobOutputStreamImpl]) = {
        val pe = jobs(stopName);
        var outputs: JobOutputStreamImpl = null;
        try {
          runnerListener.onJobStarted(pe.getContext());
          outputs = pe.perform(inputs);
          runnerListener.onJobCompleted(pe.getContext());

          //is a checkpoint?
          if (flow.hasCheckPoint(stopName)) {
            //store dataset
            outputs.makeCheckPoint(pe.getContext());
          }
        }
        catch {
          case e: Throwable =>
            runnerListener.onJobFailed(pe.getContext());
            throw e;
        }

        outputs;
      }

      //perform stop use checkpoint
      def performStopByCheckpoint(stopName: String, inputs: Map[Edge, JobOutputStreamImpl]) = {
        val pe = jobs(stopName);

        var outputs : JobOutputStreamImpl = null
        try {
          runnerListener.onJobStarted(pe.getContext());

          //new flow process
          if (checkpointParentProcessId.equals("")) {
            println("Visit process " + stopName + "!!!!!!!!!!!!!")
            outputs = pe.perform(inputs);
            runnerListener.onJobCompleted(pe.getContext());
            //TODO: test
            outputs.showDataFrame()
            if (flow.hasCheckPoint(stopName)) {
              outputs.makeCheckPoint(pe.getContext());
            }
          }else{//read checkpoint from old process
            if(flow.hasCheckPoint(stopName)){
              val pec = pe.getContext()
              outputs = pec.getOutputStream().asInstanceOf[JobOutputStreamImpl];
              val checkpointPath = pec.get("checkpoint.path").asInstanceOf[String].stripSuffix("/") + "/" + checkpointParentProcessId + "/" + pec.getStopJob().getStopName();
              println("Visit process " + stopName + " by Checkpoint!!!!!!!!!!!!!")
              outputs.loadCheckPoint(pe.getContext(),checkpointPath)
              runnerListener.onJobCompleted(pe.getContext());
            }else{
              println("Visit process " + stopName + "!!!!!!!!!!!!!")
              outputs = pe.perform(inputs);
              runnerListener.onJobCompleted(pe.getContext());
            }

          }
        }
        catch {
          case e: Throwable =>
            runnerListener.onJobFailed(pe.getContext());
            throw e;
        }

        outputs;
      }
    }

    override def run(): Unit = {
      running = true;

      //onFlowStarted
      runnerListener.onProcessStarted(processContext);
      try {
        perform();
        //onFlowCompleted
        runnerListener.onProcessCompleted(processContext);
      }
      //onFlowFailed
      catch {
        case e: Throwable =>
          runnerListener.onProcessFailed(processContext);
          throw e;
      }
      finally {
        latch.countDown();
        running = false;
      }
    }
  });

  //IMPORTANT: start thread
  workerThread.start();

  override def toString(): String = executionString;

  override def awaitTermination(): Unit = {
    latch.await();
  }

  override def awaitTermination(timeout: Long, unit: TimeUnit): Unit = {
    latch.await(timeout, unit);
    if (running)
      stop();
  }

  override def pid(): String = id;

  override def getFlow(): Flow = flow;

  private def createContext(runnerContext: Context): ProcessContext = {
    new CascadeContext(runnerContext) with ProcessContext {
      override def getFlow(): Flow = flow;

      override def getProcess(): Process = process;
    };
  }

  override def fork(child: Flow): Process = {
    //add flow process stack
    val process = new ProcessImpl(child, runnerContext, runner, Some(this));
    runnerListener.onProcessForked(processContext, process.processContext);
    process;
  }

  //TODO: stopSparkJob()
  override def stop(): Unit = {
    if (!running)
      throw new ProcessNotRunningException(this);

    workerThread.interrupt();
    runnerListener.onProcessAborted(processContext);
    latch.countDown();
  }
}

class JobContextImpl(job: StopJob, processContext: ProcessContext)
  extends CascadeContext(processContext)
    with JobContext
    with Logging {
  val is: JobInputStreamImpl = new JobInputStreamImpl();

  val os = new JobOutputStreamImpl();

  def getStopJob() = job;

  def getInputStream(): JobInputStream = is;

  def getOutputStream(): JobOutputStream = os;

  override def getProcessContext(): ProcessContext = processContext;
}

class StopJobImpl(stopName: String, stop: Stop, processContext: ProcessContext)
  extends StopJob with Logging {
  val id = "job_" + IdGenerator.nextId[StopJob];
  val pec = new JobContextImpl(this, processContext);

  override def jid(): String = id;

  def getContext() = pec;

  def perform(inputs: Map[Edge, JobOutputStreamImpl]): JobOutputStreamImpl = {
    pec.getInputStream().asInstanceOf[JobInputStreamImpl].attach(inputs);
    stop.perform(pec.getInputStream(), pec.getOutputStream(), pec);
    pec.getOutputStream().asInstanceOf[JobOutputStreamImpl];
  }

  override def getStopName(): String = stopName;

  override def getStop(): Stop = stop;
}

trait Context {
  def get(key: String): Any;

  def get(key: String, defaultValue: Any): Any;

  def get[T]()(implicit m: Manifest[T]): T = {
    get(m.runtimeClass.getName).asInstanceOf[T];
  }

  def put(key: String, value: Any): this.type;

  def put[T](value: T)(implicit m: Manifest[T]): this.type =
    put(m.runtimeClass.getName, value);
}

class CascadeContext(parent: Context = null) extends Context with Logging {
  val map = MMap[String, Any]();

  override def get(key: String): Any = internalGet(key,
    () => throw new ParameterNotSetException(key));

  override def get(key: String, defaultValue: Any): Any = internalGet(key,
    () => {
      logger.warn(s"value of '$key' not set, using default: $defaultValue");
      defaultValue;
    });

  def internalGet(key: String, op: () => Unit): Any = {
    if (map.contains(key)) {
      map(key);
    }
    else {
      if (parent != null)
        parent.get(key);
      else
        op();
    }
  };

  override def put(key: String, value: Any): this.type = {
    map(key) = value;
    this;
  }
}

class FlowException(msg: String = null, cause: Throwable = null) extends RuntimeException(msg, cause) {

}

class NoInputAvailableException extends FlowException() {

}

class ParameterNotSetException(key: String) extends FlowException(s"parameter not set: $key") {

}

//sub flow
class FlowAsStop(flow: Flow) extends Stop {
  override def initialize(ctx: ProcessContext): Unit = {
  }

  override def perform(in: JobInputStream, out: JobOutputStream, pec: JobContext): Unit = {
    pec.getProcessContext().getProcess().fork(flow).awaitTermination();
  }
}

class ProcessNotRunningException(process: Process) extends FlowException() {

}

class InvalidPathException(head: Any) extends FlowException() {

}