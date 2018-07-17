package com.datastax.spark.connector.cql

import com.datastax.driver.core.{Row, Session, Statement}
import com.datastax.spark.connector.CassandraRowMetadata
import com.datastax.spark.connector.rdd.ReadConf
import com.datastax.spark.connector.rdd.reader.PrefetchingResultSetIterator
import com.datastax.spark.connector.util.maybeExecutingAs
import com.datastax.spark.connector.writer.RateLimiter

/**
  * Object which will be used in Table Scanning Operations.
  * One Scanner will be created per Spark Partition, it will be
  * created at the beginning of the compute method and Closed at the
  * end of the compute method.
  */
trait Scanner {
  def close(): Unit
  def getSession(): Session
  def scan(statement: Statement): ScanResult
}

case class ScanResult (rows: Iterator[Row], metadata: CassandraRowMetadata)

class DefaultScanner (
    readConf: ReadConf,
    connConf: CassandraConnectorConf,
    columnNames: IndexedSeq[String]) extends Scanner {

  private val session = new CassandraConnector(connConf).openSession()

  override def close(): Unit = {
    session.close()
  }

  override def scan(statement: Statement): ScanResult = {
    val rs = session.execute(maybeExecutingAs(statement, readConf.executeAs))
    val columnMetaData = CassandraRowMetadata.fromResultSet(columnNames, rs)
    val prefetchingIterator = new PrefetchingResultSetIterator(rs, readConf.fetchSizeInRows)
    val rateLimitingIterator = readConf.throughputMiBPS match
    {
      case Some(throughput) =>
        val rateLimiter = new RateLimiter((throughput * 1024 * 1024).toLong, 1024 * 1024)
        prefetchingIterator.map{ row =>
          rateLimiter.maybeSleep(getRowBinarySize(row))
          row
        }
      case None =>
        prefetchingIterator
    }

    ScanResult(rateLimitingIterator, columnMetaData)
  }

  override def getSession(): Session = session
}