/* test with spark-shell
 * sudo spark-shell --packages org.apache.spark:spark-sql-kafka-0-10_2.11:2.4.3 org.apache.kafka:kafka_2.11:1.0.1 --jars /usr/hdp/current/hive_warehouse_connector/hive-warehouse-connector-assembly-1.0.0.3.1.2.1-1.jar
*/

import com.hortonworks.hwc.HiveWarehouseSession
import org.apache.spark.SparkConf
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.functions.udf
import com.hortonworks.hwc.HiveWarehouseSession
import com.hortonworks.hwc.HiveWarehouseSession._
import org.apache.kafka.common.security.plain.PlainLoginModule
import org.apache.spark.sql.{DataFrame, SaveMode, SparkSession}

//helper method to convert data types
def toInt(s: String): Int = {
  try {
    s.toInt
  } catch {
    case e: Exception => 0
  }
}

val conf = new SparkConf()
conf.setAppName("HWC Test")
val spark = SparkSession.builder().config(conf).enableHiveSupport().getOrCreate()

//start here
import spark.implicits._
val hive = com.hortonworks.spark.sql.hive.llap.HiveWarehouseBuilder.session(spark).build()
val TOPIC = "spark-test"
val BOOTSTRAP_SERVERS = "<YOUR.EVENTHUB.FQDN>:9093"
val EH_SASL = "<EH-CONNECTION-STRING>"
//not used in spark 2.4.2 with kafka because system will auto-generate group id
//val GROUP_ID = "mygroupid"
val STREAM_TO_STREAM = "com.hortonworks.spark.sql.hive.llap.streaming.HiveStreamingDataSource"
//Make sure permissions are set for the checkpoint location.
val CHECKPOINT_LOCATION = "/tmp/checkpoint"
val HIVE_TABLE_NAME = "stream_table"
val HIVE_DB_NAME = "testdb"

//read batch
val df = spark.read
  .format("kafka")
  .option("subscribe", TOPIC)
  .option("kafka.bootstrap.servers", BOOTSTRAP_SERVERS)
  .option("kafka.sasl.mechanism", "PLAIN")
  .option("kafka.security.protocol", "SASL_SSL")
  .option("kafka.sasl.jaas.config", EH_SASL)
  .option("kafka.request.timeout.ms", "60000")
  .option("kafka.session.timeout.ms", "60000")
  .option("failOnDataLoss", "false")
  .load()

val convertToString = udf((payload: Array[Byte]) => new String(payload))
val convertToInt = udf((payload: Array[Byte]) => toInt(new String(payload)))
hive.setDatabase(HIVE_DB_NAME)

hive.createTable(HIVE_TABLE_NAME)
  .ifNotExists()
  .column("key","int")
  .column("value","string")
  .create()

hive.table(HIVE_TABLE_NAME).show() //sanity check

//write batch
df.filter($"key".isNotNull)
  .withColumn("key", convertToInt(df("key")))
  .withColumn("value", convertToString(df("value")))
  .select("key","value")
  .write
  .format("com.hortonworks.spark.sql.hive.llap.HiveStreamingDataSource")
  .mode(SaveMode.Append)
  .option("table", HIVE_TABLE_NAME)
  .save()