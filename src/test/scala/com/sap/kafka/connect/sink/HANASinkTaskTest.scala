package com.sap.kafka.connect.sink

import java.sql.{Connection, DatabaseMetaData, Driver, DriverManager, PreparedStatement, ResultSet, ResultSetMetaData, Statement, Types}
import java.util
import java.util.Collections

import com.sap.kafka.connect.MockJdbcDriver
import com.sap.kafka.connect.config.BaseConfigConstants
import com.sap.kafka.connect.sink.hana.HANASinkTask
import org.apache.kafka.connect.data.{Schema, SchemaBuilder, Struct}
import org.apache.kafka.connect.sink.{SinkRecord, SinkTaskContext}
import org.mockito.Mockito._
import org.mockito.ArgumentMatchers
import org.scalatest.{BeforeAndAfterAll, FunSuite}

import scala.collection.JavaConverters._

class HANASinkTaskTest extends FunSuite with BeforeAndAfterAll {
  private val TEST_CONNECTION_URL= s"jdbc:sapmock://demo.hana.sap.com:50000/"
  private val TOPIC = "test-topic"
  private val SINGLE_TABLE_NAME_FOR_INSERT = "\"TEST\".\"EMPLOYEES_SINK\""

  private var namedDriver: Driver = _
  private var mockDriver: Driver = _
  private var mockTaskContext: SinkTaskContext = _
  private var mockConnection: Connection = _
  private var mockStatement: Statement = _
  private var mockPreparedStatement: PreparedStatement = _

  override def beforeAll(): Unit = {
    super.beforeAll()
    mockDriver = mock(classOf[Driver])
    when(mockDriver.acceptsURL(TEST_CONNECTION_URL)).thenReturn(true)
    when(mockDriver.getMajorVersion()).thenReturn(1)

    namedDriver = new MockJdbcDriver(mockDriver)
    DriverManager.registerDriver(namedDriver)
  }

  override def afterAll(): Unit = {
    DriverManager.deregisterDriver(namedDriver)
    super.afterAll()
  }

  val valueSchema = SchemaBuilder.struct()
    .name("schema for single table")
    .field("id", Schema.INT32_SCHEMA)
  val value = new Struct(valueSchema).put("id", 23)

  val keySchema = SchemaBuilder.struct()
    .name("schema for single table")
    .field("id", Schema.INT32_SCHEMA)
  val key = new Struct(keySchema).put("id", 23)

  val valueDeletableSchema = SchemaBuilder.struct()
    .name("schema for single table with __deleted")
    .field("id", Schema.INT32_SCHEMA)
    .field("__deleted", Schema.STRING_SCHEMA)
  val valueDeleted = new Struct(valueDeletableSchema).put("id", 23).put("__deleted", "true")

  test("sink create and insert(default)") {
    verifySinkTask(null, null, null, valueSchema, value)
  }

  test("sink create and insert") {
    verifySinkTask(BaseConfigConstants.INSERT_MODE_INSERT, null, null, valueSchema, value)
  }

  test("sink create and upsert") {
    verifySinkTask(BaseConfigConstants.INSERT_MODE_UPSERT, null, null, valueSchema, value)
  }

  test("sink create and insert with key-schema") {
    verifySinkTask(BaseConfigConstants.INSERT_MODE_INSERT, keySchema, key, valueSchema, value)
  }

  test("sink delete with upsert delete-disabled") {
    verifySinkTaskWithDelete(BaseConfigConstants.INSERT_MODE_UPSERT, null, keySchema, key, valueDeletableSchema, valueDeleted)
  }

  test("sink delete with upsert delete-enabled") {
    verifySinkTaskWithDelete(BaseConfigConstants.INSERT_MODE_UPSERT, "true", keySchema, key, valueDeletableSchema, valueDeleted)
  }

  test("sink delete with insert delete-enabled") {
    verifySinkTaskWithDelete(BaseConfigConstants.INSERT_MODE_INSERT, "true", keySchema, key, valueDeletableSchema, valueDeleted)
  }

  def verifySinkTask(insertMode: String, keySchema: Schema, key: Any, valueSchema: Schema, value: Any): Unit = {
    mockTaskContext = mock(classOf[SinkTaskContext])
    mockConnection = mock(classOf[Connection])
    mockStatement = mock(classOf[Statement])
    mockPreparedStatement = mock(classOf[PreparedStatement])
    val mockDatabaseMetaData = mock(classOf[DatabaseMetaData])
    val mockResultSetTableExists = mock(classOf[ResultSet])
    val mockResultSetSelectMetaData = mock(classOf[ResultSet])
    val mockResultSetMetaData = mock(classOf[ResultSetMetaData])

    when(mockDriver.connect(ArgumentMatchers.anyString, ArgumentMatchers.any(classOf[java.util.Properties]))).thenReturn(mockConnection)
    when(mockConnection.getMetaData).thenReturn(mockDatabaseMetaData)
    when(mockDatabaseMetaData.getTables(null, "TEST", "EMPLOYEES_SINK", null)).thenReturn(mockResultSetTableExists)
    when(mockConnection.createStatement).thenReturn(mockStatement)

    when(mockStatement.execute(ArgumentMatchers.anyString)).thenReturn(true)
    when(mockStatement.executeQuery(ArgumentMatchers.anyString)).thenReturn(mockResultSetSelectMetaData)
    when(mockResultSetSelectMetaData.getMetaData).thenReturn(mockResultSetMetaData)
    when(mockResultSetMetaData.getColumnCount).thenReturn(1)
    when(mockResultSetMetaData.getColumnName(1)).thenReturn("id")
    when(mockResultSetMetaData.getColumnType(1)).thenReturn(Types.INTEGER)
    when(mockResultSetMetaData.isNullable(1)).thenReturn(ResultSetMetaData.columnNoNulls)
    when(mockConnection.prepareStatement(ArgumentMatchers.anyString)).thenReturn(mockPreparedStatement)

    val task: HANASinkTask = new HANASinkTask()
    task.initialize(mockTaskContext)

    task.start(singleTableConfig(insertMode, "false"))

    task.put(Collections.singleton(new SinkRecord(TOPIC, 1, keySchema, key, valueSchema, value, 0)))

    val stmt = insertMode match {
      case BaseConfigConstants.INSERT_MODE_UPSERT =>
        "UPSERT \"TEST\".\"EMPLOYEES_SINK\" (\"id\") VALUES (?) WITH PRIMARY KEY"
      case _ =>
        "INSERT INTO \"TEST\".\"EMPLOYEES_SINK\" (\"id\") VALUES (?)"
    }
    verify(mockStatement).execute("CREATE COLUMN TABLE \"TEST\".\"EMPLOYEES_SINK\" (\"id\" INTEGER NOT NULL)")
    verify(mockConnection).prepareStatement(stmt)
    verify(mockPreparedStatement).setInt(1, 23)
    verify(mockPreparedStatement).addBatch()
    verify(mockPreparedStatement).executeBatch
    verify(mockPreparedStatement).clearParameters()
    verify(mockPreparedStatement).close()
  }


  def verifySinkTaskWithDelete(insertMode: String, deleteEnabled: String, keySchema: Schema, key: Any, valueSchema: Schema, value: Any): Unit = {
    mockTaskContext = mock(classOf[SinkTaskContext])
    mockConnection = mock(classOf[Connection])
    mockStatement = mock(classOf[Statement])
    mockPreparedStatement = mock(classOf[PreparedStatement])
    val mockPreparedStatement2 = mock(classOf[PreparedStatement])
    val mockDatabaseMetaData = mock(classOf[DatabaseMetaData])
    val mockResultSetTableExists = mock(classOf[ResultSet])
    val mockResultSetSelectMetaData = mock(classOf[ResultSet])
    val mockResultSetMetaData = mock(classOf[ResultSetMetaData])

    when(mockDriver.connect(ArgumentMatchers.anyString, ArgumentMatchers.any(classOf[java.util.Properties]))).thenReturn(mockConnection)
    when(mockConnection.getMetaData).thenReturn(mockDatabaseMetaData)
    when(mockResultSetTableExists.next()).thenReturn(true)
    when(mockDatabaseMetaData.getTables(null, "TEST", "EMPLOYEES_SINK", null)).thenReturn(mockResultSetTableExists)
    when(mockConnection.createStatement).thenReturn(mockStatement)

    when(mockStatement.execute(ArgumentMatchers.anyString)).thenReturn(true)
    when(mockStatement.executeQuery(ArgumentMatchers.anyString)).thenReturn(mockResultSetSelectMetaData)
    when(mockResultSetSelectMetaData.getMetaData).thenReturn(mockResultSetMetaData)
    when(mockResultSetMetaData.getColumnCount).thenReturn(2)
    when(mockResultSetMetaData.getColumnName(1)).thenReturn("id")
    when(mockResultSetMetaData.getColumnType(1)).thenReturn(Types.INTEGER)
    when(mockResultSetMetaData.getColumnName(2)).thenReturn("__deleted")
    when(mockResultSetMetaData.getColumnType(2)).thenReturn(Types.VARCHAR)
    when(mockResultSetMetaData.isNullable(1)).thenReturn(ResultSetMetaData.columnNoNulls)
    when(mockConnection.prepareStatement(ArgumentMatchers.anyString)).thenReturn(mockPreparedStatement, mockPreparedStatement2)

    val task: HANASinkTask = new HANASinkTask()
    task.initialize(mockTaskContext)

    task.start(singleTableConfig(insertMode, deleteEnabled))

    // assuming a tombstone record is generated
    task.put(util.Arrays.asList(
      new SinkRecord(TOPIC, 1, keySchema, key, valueSchema, value, 0),
      new SinkRecord(TOPIC, 1, keySchema, key, null, null, 1)
    ))

    val stmt = insertMode match {
      case BaseConfigConstants.INSERT_MODE_UPSERT =>
        "UPSERT \"TEST\".\"EMPLOYEES_SINK\" (\"id\", \"__deleted\") VALUES (?, ?) WITH PRIMARY KEY"
      case _ =>
        "INSERT INTO \"TEST\".\"EMPLOYEES_SINK\" (\"id\", \"__deleted\") VALUES (?, ?)"
    }
    val stmt2 = deleteEnabled match {
      case "true" =>
        "DELETE FROM \"TEST\".\"EMPLOYEES_SINK\" WHERE \"id\" = ?"
      case _ =>
        null
    }
    verify(mockConnection, times(3)).setAutoCommit(false)
    verify(mockConnection).prepareStatement(stmt)
    if (deleteEnabled != "true") {
      // upsert or insert a pseudo-delete record
      verify(mockPreparedStatement).setInt(1, 23)
      verify(mockPreparedStatement).setString(2, "true")
      verify(mockPreparedStatement).addBatch()
      verify(mockPreparedStatement).executeBatch
      verify(mockPreparedStatement).clearParameters()
    } else {
      // delete the record
      verify(mockConnection).prepareStatement(stmt2)
      verify(mockPreparedStatement2).setInt(1, 23)
      //TODO change the check after making the delete is performed in a batch
      verify(mockPreparedStatement2).execute()
      verify(mockPreparedStatement2).close()
    }
    verify(mockPreparedStatement).close()
  }


  protected def singleTableConfig(insertMode: String, deleteEnabled: String): java.util.Map[String, String] = {
    val props = new util.HashMap[String, String]()

    props.put("connection.url", TEST_CONNECTION_URL)
    props.put("connection.user", "sa")
    props.put("connection.password", "sa")
    props.put("auto.create", "true")
    props.put("topics", TOPIC)
    props.put(s"$TOPIC.table.name", SINGLE_TABLE_NAME_FOR_INSERT)
    if (insertMode != null) {
      props.put(s"$TOPIC.insert.mode", insertMode)
    }
    if (deleteEnabled != null) {
      props.put(s"$TOPIC.delete.enabled", deleteEnabled)
    }

    props
  }
}
