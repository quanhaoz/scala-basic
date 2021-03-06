package org.apache.spark

import java.sql.{Connection, PreparedStatement, ResultSet}

import com.horizonio.spark.core.CustomizedJdbcPartition
import org.apache.spark.api.java.JavaSparkContext.fakeClassTag
import org.apache.spark.api.java.function.{Function => JFunction}
import org.apache.spark.api.java.{JavaRDD, JavaSparkContext}
import org.apache.spark.rdd.RDD
import org.apache.spark.util.NextIterator

import scala.reflect.ClassTag

class CustomizedJdbcRDD[T: ClassTag](
                                      sc: SparkContext,
                                      getConnection: () => Connection,
                                      sql: String,
                                      getCustomizedPartitions: () => Array[Partition],
                                      prepareStatement: (PreparedStatement, CustomizedJdbcPartition) => PreparedStatement,
                                      mapRow: (ResultSet) => T = CustomizedJdbcRDD.resultSetToObjectArray _)
  extends RDD[T](sc, Nil) with Logging {


  override def getPartitions: Array[Partition] = {
    getCustomizedPartitions();
  }


  override def compute(thePart: Partition, context: TaskContext): Iterator[T] = new NextIterator[T] {
    context.addTaskCompletionListener { context => closeIfNeeded() }
    val part = thePart.asInstanceOf[CustomizedJdbcPartition]
    val conn = getConnection()
    val stmt = conn.prepareStatement(sql, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)


    // setFetchSize(Integer.MIN_VALUE) is a mysql driver specific way to force streaming results,
    // rather than pulling entire resultset into memory.
    // see http://dev.mysql.com/doc/refman/5.0/en/connector-j-reference-implementation-notes.html
    try {
      if (conn.getMetaData.getURL.matches("jdbc:mysql:.*")) {
        stmt.setFetchSize(Integer.MIN_VALUE)
        logInfo("statement fetch size set to: " + stmt.getFetchSize + " to force MySQL streaming ")
      }
    } catch {
      case ex: Exception => {
        //ex.printStackTrace();
      }
    }

    prepareStatement(stmt, part)

    val rs = stmt.executeQuery()


    override def getNext: T = {
      if (rs.next()) {
        mapRow(rs)
      } else {
        finished = true
        null.asInstanceOf[T]
      }
    }


    override def close() {
      try {
        if (null != rs && !rs.isClosed()) {
          rs.close()
        }
      } catch {
        case e: Exception => logWarning("Exception closing resultset", e)
      }
      try {
        if (null != stmt && !stmt.isClosed()) {
          stmt.close()
        }
      } catch {
        case e: Exception => logWarning("Exception closing statement", e)
      }
      try {
        if (null != conn && !conn.isClosed()) {
          conn.close()
        }
        logInfo("closed connection")
      } catch {
        case e: Exception => logWarning("Exception closing connection", e)
      }
    }

  }

}


object CustomizedJdbcRDD {
  def resultSetToObjectArray(rs: ResultSet): Array[Object] = {
    Array.tabulate[Object](rs.getMetaData.getColumnCount)(i => rs.getObject(i + 1))
  }


  trait ConnectionFactory extends Serializable {
    @throws[Exception]
    def getConnection: Connection
  }


  def create[T](
                 sc: JavaSparkContext,
                 connectionFactory: ConnectionFactory,
                 sql: String,
                 getCustomizedPartitions: () => Array[Partition],
                 prepareStatement: (PreparedStatement, CustomizedJdbcPartition) => PreparedStatement,
                 mapRow: JFunction[ResultSet, T]): JavaRDD[T] = {


    val jdbcRDD = new CustomizedJdbcRDD[T](
      sc.sc,
      () => connectionFactory.getConnection,
      sql,
      getCustomizedPartitions,
      prepareStatement,
      (resultSet: ResultSet) => mapRow.call(resultSet))(fakeClassTag)


    new JavaRDD[T](jdbcRDD)(fakeClassTag)
  }


  def create(
              sc: JavaSparkContext,
              connectionFactory: ConnectionFactory,
              sql: String,
              getCustomizedPartitions: () => Array[Partition],
              prepareStatement: (PreparedStatement, CustomizedJdbcPartition) => PreparedStatement): JavaRDD[Array[Object]] = {


    val mapRow = new JFunction[ResultSet, Array[Object]] {
      override def call(resultSet: ResultSet): Array[Object] = {
        resultSetToObjectArray(resultSet)
      }
    }


    create(sc, connectionFactory, sql, getCustomizedPartitions, prepareStatement, mapRow)
  }
}
