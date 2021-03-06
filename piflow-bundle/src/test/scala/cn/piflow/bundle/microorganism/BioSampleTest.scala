package cn.piflow.bundle.microorganism

import cn.piflow.Runner
import cn.piflow.bundle.util.UnGzUtil
import cn.piflow.conf.bean.FlowBean
import cn.piflow.conf.util.{FileUtil, OptionUtil}
import org.apache.spark.sql.SparkSession
import org.h2.tools.Server
import org.junit.Test

import scala.util.parsing.json.JSON

class BioSampleTest {

  @Test
  def testBioProjetDataParse(): Unit ={

    //parse flow json
    val file = "src/main/resources/microorganism/aa.json"
    val flowJsonStr = FileUtil.fileReader(file)
    val map = OptionUtil.getAny(JSON.parseFull(flowJsonStr)).asInstanceOf[Map[String, Any]]
    println(map)

    //create flow
    val flowBean = FlowBean(map)
    val flow = flowBean.constructFlow()

    val h2Server = Server.createTcpServer("-tcp", "-tcpAllowOthers", "-tcpPort","50001").start()
    //execute flow
    val spark = SparkSession.builder()
      .master("spark://10.0.88.70:7077")
      .appName("aa")
      .config("spark.driver.memory", "4g")
      .config("spark.executor.memory", "8g")
      .config("spark.cores.max", "4")
      .config("hive.metastore.uris","thrift://10.0.88.64:9083")
      .config("spark.jars","/home/0226/piflow/out/artifacts/microoParse/microoParse.jar")
      .enableHiveSupport()
      .getOrCreate()

    val process = Runner.create()
      .bind(classOf[SparkSession].getName, spark)
      .bind("checkpoint.path", "hdfs://10.0.86.89:9000/xjzhu/piflow/checkpoints/")
      .bind("debug.path","")
      .start(flow);

    process.awaitTermination();
    val pid = process.pid();
    println(pid + "!!!!!!!!!!!!!!!!!!!!!")
    spark.close();
  }


  @Test
  def testUngz11(): Unit ={
   val sourceFile = "/ftpBioSample1/biosample.xml.gz"

    val fileNameAdd: String = sourceFile.substring(sourceFile.lastIndexOf("/")+1)
    val fileName = fileNameAdd.substring(0,fileNameAdd.length-3)
    val  sourceFileAdd = sourceFile+"1234567890"
    val savePath: String ="" + sourceFileAdd.replaceAll(fileNameAdd+"1234567890","")

    println(sourceFile)

    println(savePath)

    println(fileName)


    val filePath: String = UnGzUtil.unGz(sourceFile,savePath,fileName)
    println("????????????----->"+filePath)

  }



  @Test
  def testUngz(): Unit ={


    val inputDir ="/ftpBioSample/biosample.xml.gz"
    val savePath="/ftpBioSample/"
    val filename = "biosample.xml"

    val filePath: String = UnGzUtil.unGz(inputDir,savePath,filename)
    println("????????????----->"+filePath)
  }

}
