package tools.redis.load

import java.util.Properties
import java.util.concurrent.{TimeUnit, Executors, ExecutorService}

import org.slf4j.LoggerFactory
import tools.redis.RedisUtils

import scala.collection.mutable.ArrayBuffer
import scala.xml.XML

/**
 * Created by tsingfu on 15/6/5.
 */
object File2Hashes {

  val logger = LoggerFactory.getLogger(this.getClass)

  def main(args: Array[String]): Unit ={
    if (args.length != 1) {
      println("Error: args.length = " + args.length + "\n" + "You should specify a confXmlFile")
      System.exit(-1)
    }

    val confXmlFile = args(0)
    load2Hashes(confXmlFile)

  }

  def load2Hashes(confXmlFile: String): Unit ={
    val props = init_props_fromXml(confXmlFile)
    val servers = props.getProperty("redis.servers").trim
    val database = props.getProperty("redis.database", "0").trim.toInt
    val timeout = props.getProperty("redis.timeout").trim.toInt
    val password = props.getProperty("redis.password")

    val maxTotal = props.getProperty("jedisPool.maxTotal").trim.toInt
    val maxIdle = props.getProperty("jedisPool.maxIdle").trim.toInt
    val minIdle = props.getProperty("jedisPool.minIdle").trim.toInt

    val from = props.getProperty("load.from").trim
    val filename = props.getProperty("load.filename").trim
    val fileEncode = props.getProperty("load.fileEncode").trim
    val columnSeperator = props.getProperty("load.columnSeperator").trim

    val hashNamePrefix = props.getProperty("load.hashNamePrefix").trim
    val hashIdxes = props.getProperty("load.hashIdxes").trim.split(",").map(_.trim.toInt)
    val hashSeperator = props.getProperty("load.hashSeperator").trim

    val valueIdxes = props.getProperty("load.valueIdxes").trim.split(",").map(_.trim.toInt)
    val fieldNames = props.getProperty("load.fieldNames").trim.split(",").map(_.trim)

    val batchLimit = props.getProperty("load.batchLimit").trim.toInt
    val batchLimitForRedis = props.getProperty("load.batchLimit.redis").trim.toInt

    val numThreads = props.getProperty("load.numThreads").trim.toInt
    val loadMethod = props.getProperty("load.method").trim

    val overwrite = props.getProperty("load.overwrite").trim.toBoolean
    val appendSeperator = props.getProperty("load.appendSeperator").trim

    val startMS = System.currentTimeMillis()
    var runningTime: Long = -1

    // 初始化 jedisPool, jedis, pipeline
    val jedisPools = servers.split(",").map(server=>{
      val hostPort = server.split(":").map(_.trim)
      val host = hostPort(0)
      val port = hostPort(1).toInt
      println("host = " + host +", port ="+port)
      RedisUtils.init_jedisPool(host, port, timeout, database, password, maxTotal, maxIdle, minIdle)
    })
    val numPools = jedisPools.length
    val jedises = jedisPools.map(_.getResource)
    val pipelines = jedises.map(_.pipelined)

    //获取文件记录数
    val fileRecordsNum = scala.io.Source.fromFile(filename, fileEncode).getLines().length

    var numProcessed = 0
    var numInBatch = 0
    var numBatches = 0
    var batchArrayBuffer: ArrayBuffer[String] = null

    // 初始化线程池
    val threadPool: ExecutorService = Executors.newFixedThreadPool(numThreads)

    def jedisPoolId = numBatches % numPools

    for (line <- scala.io.Source.fromFile(filename, fileEncode).getLines()) {
      if(numInBatch == 0){
        batchArrayBuffer = new ArrayBuffer[String]()
      }
      batchArrayBuffer.append(line)

      numInBatch += 1

      if(numInBatch == batchLimit){
        println("[INFO] [numBatches = " + numBatches + ", numInBatch = " + numInBatch +"]" )
        threadPool.submit(new Load2HashesThread(batchArrayBuffer.toArray, columnSeperator,
          hashNamePrefix, hashIdxes, hashSeperator,
          fieldNames, valueIdxes,
          jedisPools(jedisPoolId),loadMethod, batchLimitForRedis, overwrite, appendSeperator))

        numBatches += 1
        numInBatch = 0
      }
    }

    if(numInBatch > 0){
      println("[INFO] [numBatches = " + numBatches + ", numInBatch = " + numInBatch +"]" )
      threadPool.submit(new Load2HashesThread(batchArrayBuffer.toArray, columnSeperator,
        hashNamePrefix, hashIdxes, hashSeperator,
        fieldNames, valueIdxes,
        jedisPools(jedisPoolId),loadMethod, batchLimitForRedis, overwrite, appendSeperator))
      numBatches += 1
      numInBatch = 0
    }


//    Thread.sleep(1 * 1000)
    threadPool.shutdown()
    threadPool.awaitTermination(Long.MaxValue, TimeUnit.DAYS)

    //释放资源
    for(i <- 0 until numPools){
      jedisPools(i).returnResourceObject(jedises(i))
    }
    jedisPools.foreach(_.close())

  }

  /**
   * 从xml文件中初始化配置
   * @param confXmlFile
   * @return
   */
  def init_props_fromXml(confXmlFile: String): Properties ={

    val conf = XML.load(confXmlFile)
    val servers = (conf \ "redis" \ "servers").text.trim

    val database = (conf \ "redis" \ "database").text.trim
    val timeout = (conf \ "redis" \ "timeout").text.trim
    val passwd = (conf \ "redis" \ "password").text.trim
    val password = if (passwd == "" || passwd == null) null else passwd

    val maxTotal = (conf \ "jedisPool" \ "maxTotal").text.trim
    val maxIdle = (conf \ "jedisPool" \ "maxIdle").text.trim
    val minIdle = (conf \ "jedisPool" \ "minIdle").text.trim

    val from = (conf \ "load" \ "from").text.trim
    val filename = (conf \ "load" \ "filename").text.trim
    val fileEncode = (conf \ "load" \ "fileEncode").text.trim
    val columnSeperator = (conf \ "load" \ "columnSeperator").text.trim

    val hashNamePrefix = (conf \ "load" \ "hashNamePrefix").text.trim
    val hashIdxes = (conf \ "load" \ "hashIdxes").text.trim
    val hashSeperator = (conf \ "load" \ "hashSeperator").text.trim
    val valueIdxes = (conf \ "load" \ "valueIdxes").text.trim
    val fieldNames = (conf \ "load" \ "fieldNames").text.trim

    val batchLimit = (conf \ "load" \ "batchLimit").text.trim
    val batchLimitForRedis = (conf \ "load" \ "batchLimit.redis").text.trim
    val numThreads = (conf \ "load" \ "numThreads").text.trim
    val loadMethod = (conf \ "load" \ "method").text.trim
    val overwrite = (conf \ "load" \ "overwrite").text.trim
    val appendSeperator = (conf \ "load" \ "appendSeperator").text.trim


    val props = new Properties()
    props.put("redis.servers", servers)
    props.put("redis.database", database)
    props.put("redis.timeout", timeout)

    if(password != null || password == "") props.put("redis.password", password)

    props.put("jedisPool.maxTotal", maxTotal)
    props.put("jedisPool.maxIdle", maxIdle)
    props.put("jedisPool.minIdle", minIdle)

    props.put("load.from", from)
    props.put("load.filename", filename)
    props.put("load.fileEncode", fileEncode)
    props.put("load.columnSeperator", columnSeperator)

    props.put("load.hashNamePrefix", hashNamePrefix)
    props.put("load.hashIdxes", hashIdxes)
    props.put("load.hashSeperator", hashSeperator)

    props.put("load.valueIdxes", valueIdxes)
    props.put("load.fieldNames", fieldNames)

    props.put("load.batchLimit", batchLimit)
    props.put("load.batchLimit.redis", batchLimitForRedis)
    props.put("load.numThreads", numThreads)
    props.put("load.method", loadMethod)

    props.put("load.overwrite", overwrite)
    props.put("load.appendSeperator", appendSeperator)


    println("="*80)
    props.list(System.out)
    println("="*80)

    props
  }
}
