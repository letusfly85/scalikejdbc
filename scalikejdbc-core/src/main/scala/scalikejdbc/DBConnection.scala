package scalikejdbc

import java.sql.{ DatabaseMetaData, Connection }
import scalikejdbc.metadata.{ Index, ForeignKey, Column, Table }
import scala.concurrent.{ ExecutionContext, Future }
import scala.util.control.Exception._
import scala.util.control.ControlThrowable
import java.util.Locale.{ ENGLISH => en }

/**
 * Basic Database Accessor which holds a JDBC connection.
 */
trait DBConnection extends LogSupport with LoanPattern {

  type RSTraversable = ResultSetTraversable

  /**
   * Connection wil be closed automatically by default.
   */
  private[this] var autoCloseEnabled: Boolean = true

  /**
   * Provides default TxBoundary type class instance.
   */
  private[this] def defaultTxBoundary[A]: TxBoundary[A] = TxBoundary.Exception.exceptionTxBoundary[A]

  /**
   * Switches auto close mode.
   * @param autoClose auto close enabled if true
   */
  def autoClose(autoClose: Boolean): DBConnection = {
    this.autoCloseEnabled = autoClose
    this
  }

  /**
   * returns the additional attributes of current JDBC connection.
   */
  def connectionAttributes: DBConnectionAttributes = DBConnectionAttributes()

  /**
   * Returns current JDBC connection.
   */
  def conn: Connection

  /**
   * Returns is the current transaction is active.
   * @return result
   */
  def isTxNotActive: Boolean = {
    if (GlobalSettings.jtaDataSourceCompatible) {
      // JTA managed connection should be used as-is
      false
    } else {
      conn == null || conn.isClosed || conn.isReadOnly
    }
  }

  /**
   * Returns is the current transaction hasn't started yet.
   * @return result
   */
  def isTxNotYetStarted: Boolean = {
    if (GlobalSettings.jtaDataSourceCompatible) {
      // JTA managed connection should be used as-is
      false
    } else {
      conn != null && conn.getAutoCommit
    }
  }

  /**
   * Returns is the current transaction already started.
   * @return result
   */
  def isTxAlreadyStarted: Boolean = {
    if (GlobalSettings.jtaDataSourceCompatible) {
      true
    } else {
      conn != null && !conn.getAutoCommit
    }
  }

  private[this] def setAutoCommit(conn: Connection, readOnly: Boolean): Unit = {
    if (!GlobalSettings.jtaDataSourceCompatible) conn.setAutoCommit(readOnly)
  }

  private[this] def setReadOnly(conn: Connection, readOnly: Boolean): Unit = {
    if (!GlobalSettings.jtaDataSourceCompatible) conn.setReadOnly(readOnly)
  }

  private[this] def newTx(conn: Connection): Tx = {
    setReadOnly(conn, false)
    if (!GlobalSettings.jtaDataSourceCompatible && (isTxNotActive || isTxAlreadyStarted)) {
      throw new IllegalStateException(ErrorMessage.CANNOT_START_A_NEW_TRANSACTION)
    }
    new Tx(conn)
  }

  /**
   * Starts a new transaction and returns it.
   * @return tx
   */
  def newTx: Tx = newTx(conn)

  /**
   * Returns the current transaction.
   * If the transaction has not started yet, IllegalStateException will be thrown.
   * @return tx
   */
  def currentTx: Tx = {
    if (!GlobalSettings.jtaDataSourceCompatible && (isTxNotActive || isTxNotYetStarted)) {
      throw new IllegalStateException(ErrorMessage.TRANSACTION_IS_NOT_ACTIVE)
    }
    new Tx(conn)
  }

  /**
   * Returns the current transaction.
   * If the transaction has not started yet, IllegalStateException will be thrown.
   * @return tx
   */
  def tx: Tx = {
    handling(classOf[IllegalStateException]) by { e =>
      throw new IllegalStateException(
        ErrorMessage.TRANSACTION_IS_NOT_ACTIVE + " If you want to start a new transaction, use #newTx instead."
      )
    } apply currentTx
  }

  /**
   * Close the connection.
   */
  def close(): Unit = {
    ignoring(classOf[Throwable]) {
      conn.close()
    }
    log.debug("A Connection is closed.")
  }

  /**
   * Begins a new transaction.
   */
  def begin(): Unit = newTx.begin()

  /**
   * Begins a new transaction if the other one does not already start.
   */
  def beginIfNotYet(): Unit = {
    ignoring(classOf[IllegalStateException]) apply {
      begin()
    }
  }

  /**
   * Commits the current transaction.
   */
  def commit(): Unit = tx.commit()

  /**
   * Rolls back the current transaction.
   */
  def rollback(): Unit = tx.rollback()

  /**
   * Rolls back the current transaction if the transaction is still active.
   */
  def rollbackIfActive(): Unit = {
    ignoring(classOf[IllegalStateException]) apply {
      tx.rollbackIfActive()
    }
  }

  /**
   * Returns read-only session.
   * @return session
   */
  def readOnlySession(): DBSession = {
    setReadOnly(conn, true)
    DBSession(conn, isReadOnly = true, connectionAttributes = connectionAttributes)
  }

  /**
   * Provides read-only session block.
   * @param execution block
   * @tparam A  return type
   * @return result value
   */
  def readOnly[A](execution: DBSession => A): A = {
    if (autoCloseEnabled) using(conn)(_ => execution(readOnlySession()))
    else execution(readOnlySession())
  }

  /**
   * Provides read-only session block.
   * @param execution block
   * @tparam A  return type
   * @return result value
   */
  def readOnlyWithConnection[A](execution: Connection => A): A = {
    readOnly(s => execution(s.conn))
  }

  /**
   * Returns auto-commit session.
   * @return session
   */
  def autoCommitSession(): DBSession = {
    setReadOnly(conn, false)
    setAutoCommit(conn, true)
    DBSession(conn, connectionAttributes = connectionAttributes)
  }

  /**
   * Provides auto-commit session block.
   * @param execution block
   * @tparam A  return type
   * @return result value
   */
  def autoCommit[A](execution: DBSession => A): A = {
    if (autoCloseEnabled) using(conn)(_ => execution(autoCommitSession()))
    else execution(autoCommitSession())
  }

  /**
   * Provides auto-commit session block.
   * @param execution block
   * @tparam A  return type
   * @return result value
   */
  def autoCommitWithConnection[A](execution: Connection => A): A = {
    autoCommit(s => execution(s.conn))
  }

  /**
   * Returns within-tx session.
   * @return session
   */
  def withinTxSession(tx: Tx = currentTx): DBSession = {
    if (!GlobalSettings.jtaDataSourceCompatible && !tx.isActive) {
      throw new IllegalStateException(ErrorMessage.TRANSACTION_IS_NOT_ACTIVE)
    }
    DBSession(conn, tx = Some(tx), connectionAttributes = connectionAttributes)
  }

  /**
   * Provides within-tx session block.
   * @param execution block
   * @tparam A  return type
   * @return result value
   */
  def withinTx[A](execution: DBSession => A): A = {
    execution(withinTxSession(currentTx))
  }

  /**
   * Provides within-tx session block.
   * @param execution block
   * @tparam A  return type
   * @return result value
   */
  def withinTxWithConnection[A](execution: Connection => A): A = {
    withinTx(s => execution(s.conn))
  }

  private[this] def begin(tx: Tx): Unit = {
    tx.begin()
    if (!GlobalSettings.jtaDataSourceCompatible && !tx.isActive) {
      throw new IllegalStateException(ErrorMessage.TRANSACTION_IS_NOT_ACTIVE)
    }
  }

  private[this] def rollbackIfThrowable[A](f: => A): A = try {
    f
  } catch {
    case e: ControlThrowable =>
      tx.commit()
      throw e
    case e: Throwable =>
      tx.rollback()
      throw e
  }

  /**
   * Provides local-tx session block.
   * @param execution block
   * @tparam A  return type
   * @return result value
   */
  def localTx[A](execution: DBSession => A)(implicit boundary: TxBoundary[A] = defaultTxBoundary[A]): A = {
    val doClose = if (autoCloseEnabled) () => conn.close() else () => ()
    val tx = newTx
    begin(tx)
    val txResult = try {
      rollbackIfThrowable[A] {
        val session = DBSession(conn, tx = Option(tx), connectionAttributes = connectionAttributes)
        val result: A = execution(session)
        boundary.finishTx(result, tx)
      }
    } catch {
      case e: Throwable => doClose(); throw e
    }
    boundary.closeConnection(txResult, doClose)
  }

  /**
   * Easy way to checkout the current connection to be used in a transaction
   * that needs to be committed/rolled back depending on Future results.
   * @param execution block that takes a session and returns a future
   * @tparam A future result type
   * @return future result
   */
  def futureLocalTx[A](execution: DBSession => Future[A])(implicit ec: ExecutionContext): Future[A] = {
    // Enable TxBoundary implicits
    import scalikejdbc.TxBoundary.Future._
    localTx(execution)
  }

  /**
   * Provides local-tx session block.
   * @param execution block
   * @tparam A  return type
   * @return result value
   */
  def localTxWithConnection[A](execution: Connection => A)(implicit boundary: TxBoundary[A] = defaultTxBoundary[A]): A = {
    localTx(s => execution(s.conn))
  }

  /**
   * Splits the name to schema and table name
   *
   * @param name name
   * @return schema and table
   */
  private[this] def toSchemaAndTable(name: String): (String, String) = {
    val schema = {
      if (name.split("\\.").size > 1) name.split("\\.").head
      else null
    }
    val table = if (name.split("\\.").size > 1) name.split("\\.")(1) else name
    (schema, table)
  }

  /**
   * Returns all the table information that match the pattern
   *
   * @param tableNamePattern table name pattern (with schema optionally)
   * @return table information
   */
  def getTableNames(tableNamePattern: String = "%", tableTypes: Array[String] = Array("TABLE", "VIEW")): List[String] = {
    readOnlyWithConnection { conn =>
      val meta = conn.getMetaData
      getSchemaAndTableName(meta, tableNamePattern.replaceAll("\\*", "%"), tableTypes).map {
        case (schema, tableNamePattern) =>
          new RSTraversable(meta.getTables(null, schema, tableNamePattern, tableTypes))
            .map { rs =>
              val schemaName = rs.string("TABLE_SCHEM")
              if (schema != null && schema.nonEmpty && schemaName != null) {
                schemaName + "." + rs.string("TABLE_NAME")
              } else {
                rs.string("TABLE_NAME")
              }
            }.toList
      }.getOrElse(List.empty[String])
    }
  }

  /**
   * Returns all the column names on the matched table name
   */
  def getColumnNames(tableName: String, tableTypes: Array[String] = Array("TABLE", "VIEW")): List[String] = {
    readOnlyWithConnection { conn =>
      val meta = conn.getMetaData
      getSchemaAndTableName(meta, tableName, tableTypes).map {
        case (schema, tableName) =>
          new RSTraversable(meta.getColumns(null, schema, tableName, "%")).map(_.string("COLUMN_NAME")).toList.distinct
      }
    }.getOrElse(Nil)
  }

  /**
   * Returns table information if exists
   *
   * @param table table name (with schema optionally)
   * @return table information
   */
  def getTable(table: String, tableTypes: Array[String] = Array("TABLE", "VIEW")): Option[Table] = {
    readOnlyWithConnection { conn =>
      val meta = conn.getMetaData

      getSchemaAndTableName(meta, table, tableTypes).flatMap {
        case (schema, tableName) =>
          _getTable(meta, schema, tableName, tableTypes)
      }
    }
  }

  /**
   * Returns table information if exists
   *
   * @param meta database meta data
   * @param schema schema name
   * @param table table name
   * @param tableTypes target table types
   * @return table information
   */
  private[this] def _getTable(meta: DatabaseMetaData, schema: String, table: String, tableTypes: Array[String] = Array("TABLE", "VIEW")): Option[Table] = {
    val tableList = new RSTraversable(meta.getTables(null, schema, table, tableTypes)).map {
      rs => (rs.string("TABLE_SCHEM"), rs.string("TABLE_NAME"), rs.string("REMARKS"))
    }

    tableList.headOption.map {
      case (schema, table, remarks) =>
        val pkNames: Traversable[String] = new RSTraversable(meta.getPrimaryKeys(null, schema, table)).map(rs => rs.string("COLUMN_NAME"))

        Table(
          name = table,
          schema = schema,
          description = remarks,
          columns = new RSTraversable(meta.getColumns(null, schema, table, "%")).map { rs =>
            Column(
              name = try rs.string("COLUMN_NAME") catch { case e: ResultSetExtractorException => null },
              typeCode = try rs.int("DATA_TYPE") catch { case e: ResultSetExtractorException => -1 },
              typeName = rs.string("TYPE_NAME"),
              size = try rs.int("COLUMN_SIZE") catch { case e: ResultSetExtractorException => -1 },
              isRequired = try {
                rs.string("IS_NULLABLE") != null && rs.string("IS_NULLABLE") == "NO"
              } catch { case e: ResultSetExtractorException => false },
              isPrimaryKey = try {
                pkNames.exists(_ == rs.string("COLUMN_NAME"))
              } catch { case e: ResultSetExtractorException => false },
              isAutoIncrement = try {
                // Oracle throws java.sql.SQLException: Invalid column name
                rs.string("IS_AUTOINCREMENT") != null && rs.string("IS_AUTOINCREMENT") == "YES"
              } catch { case e: ResultSetExtractorException => false },
              description = try rs.string("REMARKS") catch { case e: ResultSetExtractorException => null },
              defaultValue = try rs.string("COLUMN_DEF") catch { case e: ResultSetExtractorException => null }
            )
          }.toList.distinct,
          foreignKeys = {
            try {
              new RSTraversable(meta.getImportedKeys(null, schema, table)).map { rs =>
                ForeignKey(
                  name = rs.string("FKCOLUMN_NAME"),
                  foreignColumnName = rs.string("PKCOLUMN_NAME"),
                  foreignTableName = rs.string("PKTABLE_NAME")
                )
              }.toList.distinct
            } catch { case e: ResultSetExtractorException => Nil }
          },
          indices = {
            try {
              new RSTraversable(meta.getIndexInfo(null, schema, table, false, true))
                .foldLeft(Map[String, Index]()) {
                  case (map, rs) =>
                    val indexName = rs.string("INDEX_NAME")
                    val index = map.get(indexName).map { index =>
                      index.copy(columnNames = rs.string("COLUMN_NAME") :: index.columnNames)
                    }.getOrElse {
                      Index(
                        name = indexName,
                        columnNames = List(rs.string("COLUMN_NAME")),
                        isUnique = !rs.boolean("NON_UNIQUE"))
                    }
                    map.updated(indexName, index)
                }.map { case (k, v) => v }.toList.distinct
            } catch { case e: ResultSetExtractorException => Nil }
          }
        )
    }
  }

  /**
   * Returns table name list
   *
   * @param tableNamePattern table name pattern
   * @param tableTypes table types
   * @return table name list
   */
  def showTables(tableNamePattern: String = "%", tableTypes: Array[String] = Array("TABLE", "VIEW")): String = {
    getTableNames(tableNamePattern, tableTypes).mkString("\n")
  }

  /**
   * Returns describe style string value for the table
   *
   * @param table table name (with schema optionally)
   * @return described information
   */
  def describe(table: String): String = {
    getTable(table).map(t => t.toDescribeStyleString).getOrElse("Not found.")
  }

  /**
   * Returns schema name and table name
   *
   * @param meta database meta data
   * @param tablePattern table name (with schema optionally)
   * @param tableTypes target table types
   * @return schema name and table name
   */
  private[this] def getSchemaAndTableName(meta: DatabaseMetaData, tablePattern: String, tableTypes: Array[String]): Option[(String, String)] = {
    def _getSchemaAndTableName(meta: DatabaseMetaData, tablePattern: String, tableTypes: Array[String]): Option[(String, String)] = {
      val (_schema, table) = toSchemaAndTable(tablePattern)
      val schema = if (meta.getURL.startsWith("jdbc:h2")) {
        // H2 Database 1.4 cannot accept null for metadata retrieving columns
        // in tables that name is same as information schema (e.g.) rules
        Option(_schema).getOrElse("")
      } else {
        _schema
      }

      if (new RSTraversable(meta.getTables(null, schema, table, tableTypes)).isEmpty) {
        None
      } else {
        Some((schema, table))
      }
    }

    _getSchemaAndTableName(meta, tablePattern, tableTypes)
      .orElse(_getSchemaAndTableName(meta, tablePattern.toUpperCase(en), tableTypes))
      .orElse(_getSchemaAndTableName(meta, tablePattern.toLowerCase(en), tableTypes))
  }

}
