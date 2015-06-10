package com.asiainfo.ocdc.streaming.eventsource

import java.text.SimpleDateFormat

import com.asiainfo.ocdc.streaming._
import com.asiainfo.ocdc.streaming.eventrule.{EventRule, StreamingCache}
import com.asiainfo.ocdc.streaming.labelrule.LabelRule
import com.asiainfo.ocdc.streaming.subscribe.BusinessEvent
import com.asiainfo.ocdc.streaming.tool.CacheFactory
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.{DataFrame, SQLContext}
import org.apache.spark.streaming.StreamingContext
import org.apache.spark.streaming.dstream.DStream

import scala.collection.{immutable, mutable}
import scala.collection.mutable.{ArrayBuffer, Map}

abstract class EventSource() extends Serializable with org.apache.spark.Logging {
  var id: String = null
  var conf: EventSourceConf = null
  var shuffleNum: Int = 0

  var beginTime = MainFrameConf.get("morning8time")
  var endTime = MainFrameConf.get("afternoon8time")

  val timesdf = new SimpleDateFormat("HH:mm:ss")

  val morning8Time = timesdf.parse(beginTime).getTime
  val afternoon8Time = timesdf.parse(endTime).getTime

  protected val labelRules = new ArrayBuffer[LabelRule]
  protected val eventRules = new ArrayBuffer[EventRule]
  protected val bsEvents = new ArrayBuffer[BusinessEvent]

  def addEventRule(rule: EventRule): Unit = {
    eventRules += rule
  }

  def addLabelRule(rule: LabelRule): Unit = {
    labelRules += rule
  }

  def addBsEvent(bs: BusinessEvent): Unit = {
    bsEvents += bs
  }

  def init(conf: EventSourceConf): Unit = {
    this.conf = conf
    id = this.conf.get("id")
    shuffleNum = conf.getInt("shufflenum")
  }

  def readSource(ssc: StreamingContext): DStream[String] = {
    EventSourceReader.readSource(ssc, conf)
  }

  def transform(source: String): Option[SourceObject]

  def transformDF(sqlContext: SQLContext, labeledRDD: RDD[SourceObject]): DataFrame

  final def process(ssc: StreamingContext) = {
    val sqlContext = new SQLContext(ssc.sparkContext)
    val inputStream = readSource(ssc)
    inputStream.foreachRDD { rdd =>
      val currtime = timesdf.parse(timesdf.format(System.currentTimeMillis())).getTime

      if (currtime > morning8Time && currtime < afternoon8Time && rdd.partitions.length > 0) {
        var sourceRDD = rdd.map(transform).collect {
          case Some(source: SourceObject) => source
        }

        if (shuffleNum > 0) sourceRDD = sourceRDD.map(x => (x.generateId, x)).groupByKey(shuffleNum).flatMap(_._2)
        else sourceRDD = sourceRDD.map(x => (x.generateId, x)).groupByKey().flatMap(_._2)

        if (sourceRDD.partitions.length > 0) {
          val labeledRDD = execLabelRule(sourceRDD: RDD[SourceObject])

          val eventMap = makeEvents(sqlContext, labeledRDD)

          subscribeEvents(eventMap)

        }
      }
    }
  }

  def subscribeEvents(eventMap: Map[String, DataFrame]) {
    println(" Begin subscribe events : " + System.currentTimeMillis())
    if (eventMap.size > 0) {

      eventMap.foreach(x => {
        x._2.persist()
      })

      val bsEventIter = bsEvents.iterator

      while (bsEventIter.hasNext) {
        val bsEvent = bsEventIter.next
        bsEvent.execEvent(eventMap)
      }

      eventMap.foreach(x => {
        x._2.unpersist()
      })
    }
  }

  def makeEvents(sqlContext: SQLContext, labeledRDD: RDD[SourceObject]) = {
    val eventMap: Map[String, DataFrame] = Map[String, DataFrame]()
    if (labeledRDD.partitions.length > 0) {
      println(" Begin exec evets : " + System.currentTimeMillis())
      val df = transformDF(sqlContext, labeledRDD)
      // cache data
      df.persist
      df.printSchema()

      val f4 = System.currentTimeMillis()
      val eventRuleIter = eventRules.iterator

      while (eventRuleIter.hasNext) {
        val eventRule = eventRuleIter.next
        // handle filter first
        val filteredData = df.filter(eventRule.filterExp)
        eventMap += (eventRule.conf.get("id") -> filteredData)
      }
      logDebug(" Exec eventrules cost time : " + (System.currentTimeMillis() - f4) + " millis ! ")

      df.unpersist()
    }
    eventMap
  }

  def execLabelRule(sourceRDD: RDD[SourceObject]) = {
    println(" Begin exec labes : " + System.currentTimeMillis())

    val labelRuleArray = labelRules.toArray

    sourceRDD.mapPartitions(iter => {
      new Iterator[SourceObject] {
        private[this] var currentRow: SourceObject = _
        private[this] var currentPos: Int = -1
        private[this] var arrayBuffer: Array[SourceObject] = _

        override def hasNext: Boolean = {
          val flag = (currentPos != -1 && currentPos < arrayBuffer.length) || (iter.hasNext && fetchNext())
          flag
        }

        override def next(): SourceObject = {
          currentPos += 1
          arrayBuffer(currentPos - 1)
        }

        private final def fetchNext(): Boolean = {
          val currentArrayBuffer = new ArrayBuffer[SourceObject]
          currentPos = -1
          var totalFetch = 0
          var result = false

          val minimap = mutable.Map[String, SourceObject]()

          while (iter.hasNext && totalFetch < conf.getInt("batchsize")) {
            val currentLine = iter.next()
            minimap += ("Label:" + currentLine.generateId -> currentLine)
            totalFetch += 1
            currentPos = 0
            result = true
          }

          val cachemap_old = CacheFactory.getManager.getMultiCacheByKeys(minimap.keys.toList)
          //          val cachemap_old = CacheFactory.getManager.getByteCacheString(minimap.keys.head)

          val f2 = System.currentTimeMillis()
          val cachemap_new = minimap.map(x => {
            val key = x._1
            val value = x._2

            var rule_caches = cachemap_old.get(key).get match {
              case cache: immutable.Map[String, StreamingCache] => cache
              case null => {
                val cachemap = mutable.Map[String, StreamingCache]()
                labelRuleArray.foreach(labelRule => {
                  cachemap += (labelRule.conf.get("id") -> null)
                })

                cachemap.toMap
              }
            }

            /*var rule_caches = cachemap_old match {
              case cache: immutable.Map[String, StreamingCache] => cache
              case null => {
                val cachemap = mutable.Map[String, StreamingCache]()
                labelRuleArray.foreach(labelRule => {
                  cachemap += (labelRule.conf.get("id") -> null)
                })

                cachemap.toMap
              }
            }*/

            labelRuleArray.foreach(labelRule => {

              val cacheOpt = rule_caches.get(labelRule.conf.get("id"))
              var old_cache: StreamingCache = null
              if (cacheOpt != None) old_cache = cacheOpt.get

              val newcache = labelRule.attachLabel(value, old_cache)
              rule_caches = rule_caches.updated(labelRule.conf.get("id"), newcache)

            })
            currentArrayBuffer.append(value)

            (key -> rule_caches.asInstanceOf[Any])
          })
          println(" Exec labels cost time : " + (System.currentTimeMillis() - f2) + " millis ! ")

          //update caches to CacheManager
          CacheFactory.getManager.setMultiCache(cachemap_new)
          //          CacheFactory.getManager.setByteCacheString(cachemap_new.head._1,cachemap_new.head._2)

          arrayBuffer = currentArrayBuffer.toArray
          result
        }
      }
    })
  }
}

