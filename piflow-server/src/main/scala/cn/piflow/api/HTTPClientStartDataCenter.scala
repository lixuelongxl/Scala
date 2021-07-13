package cn.piflow.api

import org.apache.http.client.config.RequestConfig
import org.apache.http.client.methods.{CloseableHttpResponse, HttpPost}
import org.apache.http.entity.StringEntity
import org.apache.http.impl.client.HttpClientBuilder
import org.apache.http.util.EntityUtils

object HTTPClientStartDataCenter {

  def main(args: Array[String]): Unit = {

    val json =
      """
        |{
        |	"group": {
        |		"name": "xjzhu",
        |		"flows": [{
        |				"flow": {
        |					"dataCenter": "",
        |					"name": "flow1",
        |					"executorMemory": "1g",
        |					"executorNumber": "1",
        |					"uuid": "8a80d63f720cdd2301723b7461d92600",
        |					"executorCores": "1",
        |					"driverMemory": "1g",
        |					"environmentVariable": {
        |						"${SPARK_HOME}": "/opt/data/spark/bin",
        |						"${CODE}": "A00101"
        |					},
        |					"stops": [{
        |							"name": "MockData",
        |							"bundle": "cn.piflow.bundle.common.MockData",
        |							"uuid": "8a80d63f720cdd2301723b7461d92604",
        |							"properties": {
        |								"schema": "title:String, author:String, age:Int",
        |								"count": "10"
        |							},
        |							"customizedProperties": {
        |
        |							}
        |						},
        |						{
        |							"name": "FlowOutportWriter",
        |							"bundle": "cn.piflow.bundle.FlowPort.FlowOutportWriter",
        |							"uuid": "8a80d63f720cdd2301723b7461d92602",
        |							"properties": {},
        |							"customizedProperties": {
        |
        |							}
        |						}
        |					],
        |					"paths": [{
        |						"inport": "",
        |						"from": "MockData",
        |						"to": "FlowOutportWriter",
        |						"outport": ""
        |					}]
        |				}
        |			},
        |			{
        |				"flow": {
        |					"name": "flow2",
        |					"executorMemory": "1g",
        |					"executorNumber": "1",
        |					"uuid": "8a80d63f720cdd2301723b7461d92600",
        |					"executorCores": "1",
        |					"driverMemory": "1g",
        |					"environmentVariable": {
        |						"${SPARK_HOME}": "/opt/data/spark/bin",
        |						"${CODE}": "A00101"
        |					},
        |					"stops": [{
        |							"name": "FlowInportReader",
        |							"bundle": "cn.piflow.bundle.FlowPort.FlowInportReader",
        |							"uuid": "8a80d63f720cdd2301723b7461d92604",
        |							"properties": {
        |								"dataSource": ""
        |							},
        |							"customizedProperties": {
        |
        |							}
        |						},
        |						{
        |							"name": "ShowData",
        |							"bundle": "cn.piflow.bundle.external.ShowData",
        |							"uuid": "8a80d63f720cdd2301723b7461d92602",
        |							"properties": {
        |								"showNumber": "5"
        |							},
        |							"customizedProperties": {
        |
        |							}
        |						}
        |					],
        |					"paths": [{
        |						"inport": "",
        |						"from": "FlowInportReader",
        |						"to": "ShowData",
        |						"outport": ""
        |					}]
        |				}
        |			}
        |
        |		],
        |		"conditions": [{
        |     "after": "flow1",
        |			"flowOutport": "FlowOutportWriter",
        |     "flowInport": "FlowInportReader",
        |			"entry": "flow2"
        |		}],
        |		"uuid": "8a80d88d712aa8c601717c68f71e0268"
        |	}
        |}
      """.stripMargin

    val url = "http://10.0.85.83:8001/group/start"
    val timeout = 1800
    val requestConfig = RequestConfig.custom()
      .setConnectTimeout(timeout*1000)
      .setConnectionRequestTimeout(timeout*1000)
      .setSocketTimeout(timeout*1000).build()

    val client = HttpClientBuilder.create().setDefaultRequestConfig(requestConfig).build()

    val post:HttpPost = new HttpPost(url)
    post.addHeader("Content-Type", "application/json")
    post.setEntity(new StringEntity(json))


    val response:CloseableHttpResponse = client.execute(post)
    val entity = response.getEntity
    val str = EntityUtils.toString(entity,"UTF-8")
    println("Code is " + str)

    /*val map = OptionUtil.getAny(JSON.parseFull(json))d.asInstanceOf[Map[String, Any]]

    val jsonObj = JsonUtil.toJson(map)
    val str = JsonUtil.format(jsonObj)
    //convertJson = convertJson.replaceAll("\\{","\\{\t\n")


    //var convertJson1 = convertJson.replaceAll("{","{\n")
    //var convertJson2 = convertJson1.replaceAll("}","}\n")
    println("Convert Json===" + str)

    val convertMap = OptionUtil.getAny(JSON.parseFull(str)).asInstanceOf[Map[String, Any]]
    println("Convert Map===" + convertMap)*/


  }

}