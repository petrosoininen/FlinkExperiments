package com.microsoft.chgeuer

// com.microsoft.chgeuer.ScalaJob
// --topic.input test --topic.target results --bootstrap.servers localhost:9092 --zookeeper.connect localhost:2181 --group.id myGroup

import com.google.protobuf.timestamp.{Timestamp, TimestampProto}
import org.apache.flink.api.java.utils.ParameterTool
import org.apache.flink.streaming.api.TimeCharacteristic
import org.apache.flink.streaming.api.windowing.time.Time
import org.apache.flink.streaming.api.scala._
import org.apache.flink.streaming.api.windowing.assigners.TumblingEventTimeWindows
import org.apache.flink.streaming.util.serialization.{DeserializationSchema, SimpleStringSchema}
import org.apache.flink.streaming.connectors.kafka.{FlinkKafkaConsumer010, FlinkKafkaProducer010}
import com.microsoft.chgeuer.proto.messages.TrackingPacket
import org.apache.flink.api.common.typeinfo.TypeInformation

object ScalaJob {
  case class Point(ccn:String, count:Int)

  class TrackingPacketSerializer extends DeserializationSchema[TrackingPacket]
  {
    override def isEndOfStream(nextElement: TrackingPacket): Boolean = false
    override def deserialize(message: Array[Byte]): TrackingPacket = TrackingPacket.parseFrom(message)
    override def getProducedType: TypeInformation[TrackingPacket] = createTypeInformation[TrackingPacket]
  }

  def main(args: Array[String]) {
    val params = ParameterTool.fromArgs(args)

    val env = StreamExecutionEnvironment.getExecutionEnvironment
    env.getConfig.setGlobalJobParameters(params)
    env.setStreamTimeCharacteristic(TimeCharacteristic.IngestionTime)

    /*
    val p = TrackingPacket(
      ccn = "Hallo",
      lat = 1.2,
      lon = 2.3,
      date = Some(Timestamp(
        seconds = 732984719837489L,
        nanos = 1248)))
    var buf = TrackingPacket.toByteArray(p)
    */

    val messageStream = env.addSource(
        new FlinkKafkaConsumer010[TrackingPacket](
          params.getRequired("topic.input"),
          new TrackingPacketSerializer,
          params.getProperties
        )
      )
      .map(w => new Point(ccn = w.ccn, count = w.id))
      .keyBy("ccn")
      .window(TumblingEventTimeWindows.of(Time.seconds(5)))
      // .window(GlobalWindows.create).evictor(TimeEvictor.of(Time.of(10, TimeUnit.SECONDS)))
      .sum("count")
      .map(t => s"${t.ccn} ${t.count}")
      // .map(w => (w.split(' ')(0), w.split(' ')(1).toInt)).keyBy(0).sum(1).map(t => s"${t._1} ${t._2}")

    val myProducerConfig = FlinkKafkaProducer010.writeToKafkaWithTimestamps[String](
      messageStream.javaStream,
      params.getRequired("topic.target"),
      new SimpleStringSchema,
      params.getProperties
    )
    myProducerConfig.setLogFailuresOnly(false)
    myProducerConfig.setFlushOnCheckpoint(true)

    env.execute("Christian's ScalaJob")
  }
}