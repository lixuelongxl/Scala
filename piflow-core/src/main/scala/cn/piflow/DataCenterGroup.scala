package cn.piflow

import cn.piflow.util.{FlowState, H2Util, HttpClientsUtil, MapUtil}
import com.alibaba.fastjson.{JSON, JSONObject}
import com.alibaba.fastjson.serializer.SerializerFeature
import java.lang.Thread.UncaughtExceptionHandler
import java.util.{Date, UUID}
import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.AtomicInteger

import scala.collection.mutable.{ArrayBuffer, Map => MMap}
import scala.util.{Failure, Success, Try}


class DataCenterGroupImpl extends Group {
  var name = ""
  var uuid = ""
  var parentId = ""
  var edges = ArrayBuffer[Edge]();
  val _mapFlowWithConditions = MMap[String, (GroupEntry, Condition[GroupExecution])]();

  def addGroupEntry(name: String, flow: GroupEntry, con: Condition[GroupExecution] = Condition.AlwaysTrue[GroupExecution]) = {
    _mapFlowWithConditions(name) = flow -> con;
  }

  def mapFlowWithConditions(): Map[String, (GroupEntry, Condition[GroupExecution])] = _mapFlowWithConditions.toMap;


  def addPath(path: Path): Group = {
    edges ++= path.toEdges();
    this;
  }


  override def getGroupName(): String = {
    this.name
  }

  override def setGroupName(groupName: String): Unit = {
    this.name = groupName
  }

  override def getParentGroupId(): String = { "" }


  override def setParentGroupId(groupId: String): Unit = {}

  override def getEdges(): ArrayBuffer[Edge] = {
    this.edges
  }
}

class DataCenterGroupExecutionImpl(group: Group, runnerContext: Context, runner: Runner) extends GroupExecution {

  val id : String = "group_" + UUID.randomUUID().toString;

  val groupContext = createContext(runnerContext);
  val groupExecution = this;

  val mapGroupEntryWithConditions: Map[String, (GroupEntry, Condition[GroupExecution])] = group.mapFlowWithConditions();

  //completed flow
  val completedGroupEntry = MMap[String, Boolean]();
  completedGroupEntry ++= mapGroupEntryWithConditions.map(x => (x._1, false))

  // waiting flow number
  val numWaitingGroupEntry = new AtomicInteger(mapGroupEntryWithConditions.size)


  //init imcomingEdges and outgoingEdges
  val incomingEdges = MMap[String, ArrayBuffer[Edge]]();
  val outgoingEdges = MMap[String, ArrayBuffer[Edge]]();
  group.getEdges().foreach { edge =>
    incomingEdges.getOrElseUpdate(edge.stopTo, ArrayBuffer[Edge]()) += edge;//use this
    outgoingEdges.getOrElseUpdate(edge.stopFrom, ArrayBuffer[Edge]()) += edge;
  }

  //init dataCenter info
  val dataCenterMap = MMap[String, String]()
  mapGroupEntryWithConditions.foreach { en => dataCenterMap(en._1) = en._2._1.asInstanceOf[Flow].getDataCenter() }

  //flow data size
  val flowDataSize = MMap[String, Long]()

  //started flow appId Map
  val startedProcessesAppID = MMap[String, String]()

  val execution = this;
  val POLLING_INTERVAL = 1000;
  val latch = new CountDownLatch(1);
  var running = true;

  val listener = new RunnerListener {

    override def onProcessStarted(ctx: ProcessContext): Unit = {}

    override def onProcessFailed(ctx: ProcessContext): Unit = {
      //TODO: retry?
    }
    override def onProcessCompleted(ctx: ProcessContext): Unit = {

    }
    override def onProcessForked(ctx: ProcessContext, child: ProcessContext): Unit = {}

    override def onProcessAborted(ctx: ProcessContext): Unit = {}

    override def onGroupStarted(ctx: GroupContext): Unit = {}

    //TODO:!!!
    override def onGroupCompleted(ctx: GroupContext): Unit = {
      /*startedGroup.filter(_._2 == ctx.getGroupExecution()).foreach { x =>
        completedGroupEntry(x._1) = true;
        numWaitingGroupEntry.decrementAndGet();
      }*/
    }

    override def onGroupStoped(ctx: GroupContext): Unit = {}

    override def onGroupFailed(ctx: GroupContext): Unit = {}

    override def onJobInitialized(ctx: JobContext): Unit = {}

    override def onJobStarted(ctx: JobContext): Unit = {}

    override def onJobCompleted(ctx: JobContext): Unit = {}

    override def onJobFailed(ctx: JobContext): Unit = {}

    override def monitorJobCompleted(ctx: JobContext, outputs: JobOutputStream): Unit = {}

    def getDataCenterDataSource(flowIncomingEdge: ArrayBuffer[Edge]) : MMap[String, MMap[String,Any]] = {
      val returnMap = MMap[String, MMap[String,Any]]()
      flowIncomingEdge.foreach(edge => {
        val emptyMap = MMap[String, Any]()
        val fromFlow = edge.stopFrom
        val fromFlowDataCenter = dataCenterMap(fromFlow)
        val fromFlowAppID = startedProcessesAppID(fromFlow)// check exist
        val fromOutport = edge.outport

        //hdfsDir and urlAddress: in order to assign a value to the properties in FlowHttpInportReader
        val hdfsDir = "/user/piflow/dataCenter/" + fromFlowAppID + "/" + fromOutport
        emptyMap += ("hdfsDir" -> hdfsDir)
        val ipAndPortRegex = "(\\d+\\.\\d+\\.\\d+\\.\\d+)\\:(\\d{4,5})".r
        val regex = ipAndPortRegex.findFirstIn(fromFlowDataCenter)
        if (regex == None){
          throw new Exception("the format of the IP address and port is error")
        }else{
          emptyMap += ("urlAddress" -> regex.mkString)
        }
        returnMap += (edge.inport -> emptyMap)
      })

      returnMap
    }

    def flowToJsonStr(flow: Flow, dataSourceMap: MMap[String, MMap[String,Any]]) : String = {
      val flowJson = flow.getFlowJson()
      val flowJSONObject = JSON.parseObject(flowJson)
      val stopsJSONArray = flowJSONObject.getJSONObject("flow").getJSONArray("stops")
      for (index <- 0 until stopsJSONArray.size()) {
        val stopJSONObject = stopsJSONArray.getJSONObject(index)
        val stopName = stopJSONObject.getString("name")
        val dataMap = dataSourceMap.getOrElse(stopName,None)
        if (None != dataMap) {
          val tempMap = dataMap.asInstanceOf[MMap[String,Any]]
          //set hdfsDir and urlAddress for FlowHttpInportReader
          var properties = stopJSONObject.getJSONObject("properties")
          properties.put("hdfsDir",  MapUtil.get(tempMap,"hdfsDir"))
          properties.put("urlAddress",  MapUtil.get(tempMap,"urlAddress"))
          stopJSONObject.put("properties", properties)
        }
      }
      val flow3JsonNew = JSON.toJSONString(flowJSONObject, SerializerFeature.PrettyFormat, SerializerFeature.WriteMapNullValue, SerializerFeature.WriteDateUseDateFormat)

      flow3JsonNew
    }

    def getFlowInfo(flowName: String, appId: String) : JSONObject = {
      val dataCenter = dataCenterMap(flowName)
      var flowInfoJSONObject = new JSONObject()
      try {
        val doGetStr = HttpClientsUtil.doGet(dataCenter + "/flow/info?appID=" + appId, 1800);
        flowInfoJSONObject = JSON.parseObject(doGetStr).getJSONObject("flow")
      } catch {
        case e: Throwable =>
          println(e)
      }
      flowInfoJSONObject
    }

    def getFlowDataSize(flowName: String, appId: String) : JSONObject = {
      val dataCenter = dataCenterMap(flowName)
      var flowInfoJSONObject = new JSONObject()
      try {
        val doGetStr = HttpClientsUtil.doGet(dataCenter + "/flow/dataSize?appId=" + appId, 1800);
        flowInfoJSONObject = JSON.parseObject(doGetStr).getJSONObject("flow")
      } catch {
        case e: Throwable =>
          println(e)
      }
      flowInfoJSONObject
    }

    def addFlowInfo(appId: String, flowName:String) : Unit = {
      val flowState = H2Util.getFlowState(appId)
      if (flowState.length <= 0) {
        val time = new Date().toString
        H2Util.addFlow(appId, "dataCenter", flowName)
        H2Util.updateFlowState(appId,FlowState.STARTED)
        H2Util.updateFlowStartTime(appId,time)
      }
    }

    def onDataCentProcessFinished(appId: String) : Unit = {
      val time = new Date().toString
      H2Util.updateFlowFinishedTime(appId,time)
      H2Util.updateFlowState(appId,FlowState.COMPLETED)
    }

    def onDataCentProcessFailed(state: String, appId: String) : Unit = {
      val time = new Date().toString
      H2Util.updateFlowFinishedTime(appId,time)
      H2Util.updateFlowState(appId,FlowState.FAILED)
    }

    def onDataCentProcessState(state: String, appId: String) : Unit = {
      val time = new Date().toString
      H2Util.updateFlowFinishedTime(appId,time)
      H2Util.updateFlowState(appId,state)
    }

  };

  runner.addListener(listener);
  val runnerListener = runner.getListener()


  def isEntryCompleted(name: String): Boolean = {
    completedGroupEntry(name)
  }

  private def startProcess(name: String, flow: Flow, groupId: String = ""): Unit = {

    var flowJson = flow.getFlowJson()

    //TODO: replace flow json
    if (incomingEdges.contains(name)) {
      val flowIncomingEdge = incomingEdges(name)
      val emptyMap = listener.getDataCenterDataSource(flowIncomingEdge)
      if (emptyMap.size > 0) {
        flowJson = listener.flowToJsonStr(flow, emptyMap);
      }
    }

    //TODO: send request to run flow!!!!!!!!! zhoujianpeng
    //construct new flow json
    val timeoutMs = 18000
    val url = flow.getDataCenter() + "/flow/start"
    val doPostStr = HttpClientsUtil.doPost(url, timeoutMs, flowJson);
    println("Code is " + doPostStr)

    val doPostJson = JSON.parseObject(doPostStr)
    val appId : String = doPostJson.getJSONObject("flow").getString("id")
    startedProcessesAppID(name) = appId

    //TODO: save flow status by H2Util
    listener.addFlowInfo(appId, flow.getFlowName())
  }

  @volatile
  var maybeException:Option[Throwable] = None
  val pollingThread = new Thread(new Runnable() {
    override def run(): Unit = {

      runnerListener.onGroupStarted(groupContext)

      try{
        while (numWaitingGroupEntry.get() > 0) {

          val todosFlow = getTodos()

          startedProcessesAppID.synchronized {
            todosFlow.foreach(en => {
              startProcess(en._1, en._2.asInstanceOf[Flow],id)
            });
          }

          Thread.sleep(POLLING_INTERVAL);
          //TODO: check wether flow finished!!!!!!!
          completedGroupEntry.filter(x => x._2 == false).map(_._1).foreach{flowName => {

            if (startedProcessesAppID.get(flowName) != None) {
              val appId = startedProcessesAppID(flowName)
              //sent request, getFlowInfo(dataCenter,appId) zhoujianpeng
              val flowInfo = listener.getFlowInfo(flowName, appId)
              if (flowInfo.size() > 0) {
                //update flow status
                val flowState = flowInfo.getString("state")
                listener.onDataCentProcessState(flowState, appId)
                var finishedFlowName = ""
                if (flowState == "COMPLETED") {
                  completedGroupEntry(flowInfo.getString("name")) = true
                  numWaitingGroupEntry.decrementAndGet
                  //update flow data size
                  val flowDataSizeInfo = listener.getFlowDataSize(flowName, appId)
                  if (flowDataSizeInfo.size() > 0) {
                    val dataSize = flowDataSizeInfo.getString("dataSize")
                    flowDataSize(flowName) = dataSize.toLong
                  }
                  listener.onDataCentProcessFinished(appId);
                }
              }
            }

          }}
        }

        runnerListener.onGroupCompleted(groupContext)

      }catch {
        case e: Throwable =>
          runnerListener.onGroupFailed(groupContext);
          println(e)
          if(e.isInstanceOf[GroupException])
            throw e
      }
      finally {
        latch.countDown();
        finalizeExecution(true);
      }
    }
  });

  val doit = Try{
    pollingThread.setUncaughtExceptionHandler( new UncaughtExceptionHandler {
      override def uncaughtException(thread: Thread, throwable: Throwable): Unit = {
        maybeException = Some(throwable)
      }
    })
    pollingThread.start()
    //pollingThread.join()
  }

  doit match {
    case Success(v) => {
      println("Did not capture error!")
    }
    case Failure(v) =>{
      println("Capture error!")
      runnerListener.onGroupFailed(groupContext)
    }

  }

  override def awaitTermination(): Unit = {
    latch.await();
    finalizeExecution(true);
  }

  override def stop(): Unit = {
    finalizeExecution(false);
    //runnerListener.onProjectStoped(projectContext)
  }

  override def awaitTermination(timeout: Long, unit: TimeUnit): Unit = {
    if (!latch.await(timeout, unit))
      finalizeExecution(false);
  }

  private def finalizeExecution(completed: Boolean): Unit = {
    if (running) {
      if (!completed) {

        startedProcessesAppID.synchronized{
          startedProcessesAppID.filter(x => !isEntryCompleted(x._1)).foreach(x => {

            val flowName = x._1
            val appID: String = startedProcessesAppID.getOrElse(x._1,"")
            if(!appID.equals("")){

              //TODO:set request to remote DataCenter!!!!! zhoujianpeng
              //x._1
              println("Stop Flow " + appID + " by Request!")
            }

          });
        }

        pollingThread.interrupt();

      }

      runner.removeListener(listener);
      running = false;
    }
  }

  private def createContext(runnerContext: Context): GroupContext = {
    new CascadeContext(runnerContext) with GroupContext {
      override def getGroup(): Group = group

      override def getGroupExecution(): GroupExecution = groupExecution

    };
  }

  private def getTodos() : (ArrayBuffer[(String, Flow)]) = {

    val todosFlow = ArrayBuffer[(String, Flow)]();
    mapGroupEntryWithConditions.foreach { en =>

      if (!startedProcessesAppID.contains(en._1) && en._2._2.matches(execution)) {
        val flowName = en._1
        val flow = en._2._1.asInstanceOf[Flow]
        //TODO: according to upStream flow data size, determine which data center the flow should be executed.
        if(flow.getDataCenter().equals("")){
          val flowIncomingEdge = incomingEdges(flowName)
          var upstreamMaxDataSize : Long = 0
          flowIncomingEdge.foreach( edge => {
            val upstreamFlowName = edge.stopFrom
            val upstreamFlowDataCenter = dataCenterMap(upstreamFlowName)
            val upstreamFlowDataSize = flowDataSize(upstreamFlowName) //TODO: dataSize API
            if(upstreamMaxDataSize < upstreamFlowDataSize){
              upstreamMaxDataSize = upstreamFlowDataSize
              dataCenterMap(flowName) = upstreamFlowDataCenter
            }
          })
          en._2._1.asInstanceOf[Flow].setDataCenter(dataCenterMap(flowName))
        }
        todosFlow += (en._1 -> en._2._1.asInstanceOf[Flow]);
      }
    }
    todosFlow
  }

  override def getGroupId(): String = id

  override def getChildCount(): Int = {
    mapGroupEntryWithConditions.size

  }
}

