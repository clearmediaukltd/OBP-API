package code.bankconnectors

/*
Open Bank Project - API
Copyright (C) 2011-2015, TESOBE / Music Pictures Ltd

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU Affero General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
GNU Affero General Public License for more details.

You should have received a copy of the GNU Affero General Public License
along with this program. If not, see http://www.gnu.org/licenses/.

Email: contact@tesobe.com
TESOBE / Music Pictures Ltd
Osloerstrasse 16/17
Berlin 13359, Germany
*/

import java.util.{Properties, UUID}

import kafka.consumer.{Consumer, _}
import kafka.message._
import kafka.producer.{KeyedMessage, Producer, ProducerConfig}
import kafka.utils.{Json, ZKStringSerializer, ZkUtils}
import net.liftweb.util.Props
import org.I0Itec.zkclient.ZkClient

object ZooKeeperUtils {
  // gets brokers tracked by zookeeper
  def getBrokers(zookeeper:String): List[String] = {
    //connect to zookeeper client
    val zkClient = new ZkClient(zookeeper, 30000, 30000, ZKStringSerializer)
    // get list of all available kafka brokers
    val brokers = for {broker <- ZkUtils.getAllBrokersInCluster(zkClient)} yield {broker.host +":"+ broker.port}
    // close zookeeper client before returning the result
    zkClient.close()
    brokers.toList
  }
  // gets all topics tracked by zookeeper
  def getTopics(zookeeper:String): List[String] = {
    //connect to zookeeper client
    val zkClient = new ZkClient(zookeeper, 30000, 30000, ZKStringSerializer)
    // get list of all available kafka topics 
    val res = ZkUtils.getAllTopics(zkClient).toList
    zkClient.close()
    res
  }
}


class KafkaConsumer(val zookeeper: String = Props.get("kafka.zookeeper_host")openOr("localhost:2181"),
                    val groupId: String   = Props.get("kafka.group_id").openOrThrowException("no kafka.group_id set"),
                    val topic: String     = Props.get("kafka.response_topic").openOrThrowException("no kafka.response_topic set"),
                    val delay: Long       = 0) {

  val config = createConsumerConfig(zookeeper, groupId)
  val consumer = Consumer.create(config)

  def shutdown() = {
    if (consumer != null)
      consumer.shutdown()
  }
  def createConsumerConfig(zookeeper: String, groupId: String): ConsumerConfig = {
    val props = new Properties()
    props.put("zookeeper.connect", zookeeper)
    props.put("group.id", groupId)
    props.put("auto.offset.reset", "smallest")
    props.put("zookeeper.session.timeout.ms", "6000")
    props.put("zookeeper.connection.timeout.ms", "6000")
    props.put("session.timeout.ms", "6000");
    props.put("zookeeper.sync.time.ms", "1000")
    props.put("consumer.timeout.ms", "6000")
    props.put("auto.commit.enable", "true");
    props.put("auto.commit.interval.ms", "1000")
    val config = new ConsumerConfig(props)
    config
  }

  def getResponse(reqId: String): List[Map[String, String]] = {
    // create single stream for topic 
    val topicCountMap = Map(topic -> 1)
    val consumerMap = consumer.createMessageStreams(topicCountMap)
    val streams = consumerMap.get(topic).get
    // process streams
    for (stream <- streams) {
      val it = stream.iterator()
      try {
        // wait for message
        while (it.hasNext()) {
          val mIt = it.next()
          val msg = new String(mIt.message(), "UTF8")
          val key = new String(mIt.key(), "UTF8")
          // check if the id matches
          if (key == reqId) {
            // disconnect from kafka
            shutdown()
            // Parse JSON message
            val json = Json.parseFull(msg)
            val r = json.get match {
              case l: List[Map[String,String]] => l.asInstanceOf[List[Map[String, String]]]
              case m: Map[String,String] => List(m.asInstanceOf[Map[String, String]])
              case _ => List(Map("error" -> "incorrect JSON format"))
            }
            return r
          }
        }
      }
      catch {
        case e:kafka.consumer.ConsumerTimeoutException => println("Exception: " + e.toString())
        // disconnect from kafka
        shutdown()
        return List(Map("error" -> "timeout"))
      }
    }
    // disconnect from kafka
    shutdown()
    return List(Map("" -> ""))
  }
}

import code.bankconnectors.ZooKeeperUtils._

case class KafkaProducer(
                          topic: String          = Props.get("kafka.request_topic").openOrThrowException("no kafka.request_topic set"),
                          brokerList: String     = getBrokers(Props.get("kafka.zookeeper_host")openOr("localhost:2181")).mkString(","),
                          clientId: String       = UUID.randomUUID().toString,
                          synchronously: Boolean = true,
                          compress: Boolean      = true,
                          batchSize: Integer     = 200,
                          messageSendMaxRetries: Integer = 3,
                          requestRequiredAcks: Integer   = -1
                          ) {

  // determine compression codec
  val codec = if (compress) DefaultCompressionCodec.codec else NoCompressionCodec.codec

  // configure producer
  val props = new Properties()
  props.put("compression.codec", codec.toString)
  props.put("producer.type", if (synchronously) "sync" else "async")
  props.put("metadata.broker.list", brokerList)
  props.put("batch.num.messages", batchSize.toString)
  props.put("message.send.max.retries", messageSendMaxRetries.toString)
  props.put("request.required.acks", requestRequiredAcks.toString)
  props.put("client.id", clientId.toString)
  props.put("session.timeout.ms", "4000");

  // create producer
  val producer = new Producer[AnyRef, AnyRef](new ProducerConfig(props))

  // create keyed message since we will use the key as id for matching response to a request
  def kafkaMesssage(key: Array[Byte], message: Array[Byte], partition: Array[Byte]): KeyedMessage[AnyRef, AnyRef] = {
    if (partition == null) {
      // no partiton specified
      new KeyedMessage(topic, key, message)
    } else {
      // specific partition 
      new KeyedMessage(topic, key, partition, message)
    }
  }

  def send(key: String, request: String, arguments: Map[String, String], partition: String = null): Unit = {
    // create string from named map of arguments
    val args = for ( (k,v) <- arguments ) yield Json.encode(Map(k -> v) )
    // create message using request and arguments strings
    val message = Json.encode(Map(request -> args))
    // translate strings to utf8 before sending to kafka
    send(key.getBytes("UTF8"), message.getBytes("UTF8"), if (partition == null) null else partition.getBytes("UTF8"))
  }

  def send(key: Array[Byte], message: Array[Byte], partition: Array[Byte]): Unit = {
    try {
      // actually send the message to kafka
      producer.send(kafkaMesssage(key, message, partition))
    } catch {
      case e: Exception =>
        e.printStackTrace()
    }
  }
}

