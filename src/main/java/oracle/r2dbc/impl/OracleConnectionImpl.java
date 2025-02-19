/*
  Copyright (c) 2020, 2021, Oracle and/or its affiliates.

  This software is dual-licensed to you under the Universal Permissive License 
  (UPL) 1.0 as shown at https://oss.oracle.com/licenses/upl or Apache License
  2.0 as shown at http://www.apache.org/licenses/LICENSE-2.0. You may choose
  either license.

  Licensed under the Apache License, Version 2.0 (the "License");
  you may not use this file except in compliance with the License.
  You may obtain a copy of the License at

     https://www.apache.org/licenses/LICENSE-2.0

  Unless required by applicable law or agreed to in writing, software
  distributed under the License is distributed on an "AS IS" BASIS,
  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  See the License for the specific language governing permissions and
  limitations under the License.
*/

package oracle.r2dbc.impl;

import io.r2dbc.spi.Batch;
import io.r2dbc.spi.Connection;
import io.r2dbc.spi.ConnectionMetadata;
import io.r2dbc.spi.IsolationLevel;
import io.r2dbc.spi.R2dbcException;
import io.r2dbc.spi.Statement;
import io.r2dbc.spi.TransactionDefinition;
import io.r2dbc.spi.ValidationDepth;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Mono;

import java.sql.SQLException;

import static io.r2dbc.spi.IsolationLevel.READ_COMMITTED;
import static io.r2dbc.spi.IsolationLevel.SERIALIZABLE;
import static io.r2dbc.spi.TransactionDefinition.ISOLATION_LEVEL;
import static io.r2dbc.spi.TransactionDefinition.LOCK_WAIT_TIMEOUT;
import static io.r2dbc.spi.TransactionDefinition.NAME;
import static io.r2dbc.spi.TransactionDefinition.READ_ONLY;
import static java.sql.Connection.TRANSACTION_READ_COMMITTED;
import static java.sql.Connection.TRANSACTION_SERIALIZABLE;
import static oracle.r2dbc.impl.OracleR2dbcExceptions.requireNonNull;
import static oracle.r2dbc.impl.OracleR2dbcExceptions.getOrHandleSQLException;
import static oracle.r2dbc.impl.OracleR2dbcExceptions.requireOpenConnection;
import static oracle.r2dbc.impl.OracleR2dbcExceptions.runOrHandleSQLException;
import static oracle.r2dbc.impl.OracleR2dbcExceptions.toR2dbcException;

/**
 * <p>
 * Implementation of the {@link Connection} SPI for Oracle Database.
 * </p><p>
 * Instances of this class represent a session in which a user performs
 * operations on an Oracle Database. Sessions typically begin by establishing
 * a network connection to the database and then authenticating as a particular
 * user. Operations are typically specified as Structured Query Language (SQL)
 * {@linkplain #createStatement(String) statements} that store and retrieve
 * information from relational data structures. Operations occur within the
 * scope of a transaction. A transaction must either be
 * {@linkplain #commitTransaction() committed} or
 * {@linkplain #rollbackTransaction() rolled back}. If committed, then changes
 * made within the transaction become visible to other sessions. If rolled back,
 * then the changes are discarded.
 * </p><p>
 * Instances of this class operate on a {@link java.sql.Connection} from a
 * JDBC Driver. JDBC API calls are adapted into Reactive Streams APIs
 * using a {@link ReactiveJdbcAdapter}.
 * </p>
 *
 * @author  harayuanwang, michael-a-mcmahon
 * @since   0.1.0
 */
final class OracleConnectionImpl implements Connection {

  /** Adapts JDBC Driver APIs into Reactive Streams APIs */
  private final ReactiveJdbcAdapter adapter;

  /**
   * JDBC connection to an Oracle Database that this connection uses to
   * perform database operations.
   */
  private final java.sql.Connection jdbcConnection;

  /**
   * Constructs a new connection that uses the specified {@code adapter} to
   * perform database operations with the specified {@code jdbcConnection}.
   * @param adapter Adapts JDBC calls into reactive streams. Not null. Retained.
   * @param jdbcConnection JDBC connection to an Oracle Database. Not null.
   *                       Retained.
   */
  OracleConnectionImpl(
    ReactiveJdbcAdapter adapter, java.sql.Connection jdbcConnection) {
    this.adapter = adapter;
    this.jdbcConnection = jdbcConnection;
  }

  /**
   * {@inheritDoc}
   * <p>
   * Implements the R2DBC SPI method by executing a {@code SET TRANSACTION}
   * command to explicitly begin a transaction on the Oracle Database to which
   * JDBC is connected.
   * </p><p>
   * Oracle Database supports transactions that begin <i>implicitly</i>
   * when executing SQL statements that modify data, or when a executing a
   * {@code SELECT ... FOR UPDATE} command. This functionality is accessible
   * with the Oracle R2DBC Driver, but R2DBC applications should not rely on
   * it. For maximum portability between R2DBC drivers, applications should
   * explicitly begin transactions by invoking this method.
   * </p><p>
   * The returned publisher begins a transaction <i>after</i> a subscriber
   * subscribes, <i>before</i> the subscriber emits a {@code request}
   * signal. Multiple subscribers are supported, but the returned publisher
   * does not repeat the action of beginning a transaction for each
   * subscription. Any signals emitted to the first subscription are
   * propagated to subsequent subscriptions.
   * </p>
   * @throws IllegalStateException If this {@code Connection} is closed
   */
  @Override
  public Publisher<Void> beginTransaction() {
    requireOpenConnection(jdbcConnection);

    final IsolationLevel isolationLevel;
    int jdbcIsolationLevel =
      getOrHandleSQLException(jdbcConnection::getTransactionIsolation);

    // Map JDBC's isolation level to an R2DBC IsolationLevel
    switch (jdbcIsolationLevel) {
      case TRANSACTION_READ_COMMITTED:
        isolationLevel = READ_COMMITTED;
        break;
      case TRANSACTION_SERIALIZABLE:
        isolationLevel = SERIALIZABLE;
        break;
      default:
        // In 21c, Oracle only supports READ COMMITTED or SERIALIZABLE. Any
        // other level is unexpected and has not been verified with test cases.
        throw new IllegalArgumentException(
          "Unrecognized JDBC transaction isolation level: "
            + jdbcIsolationLevel);
    }

    return beginTransaction(isolationLevel);
  }

  /**
   * {@inheritDoc}
   * <p>
   * Implements the R2DBC SPI method by executing a {@code SET TRANSACTION}
   * command to explicitly begin a transaction on the Oracle Database to which
   * JDBC is connected.
   * </p><p>
   * The attributes of the {@code definition} specify parameters of the
   * {@code SET TRANSACTION} command:
   * </p><dl>
   *   <dt>{@link TransactionDefinition#ISOLATION_LEVEL}</dt>
   *   <dd>
   *     Specifies the argument to an ISOLATION LEVEL clause. Only READ
   *     COMMITTED is supported in this release of Oracle R2DBC. An
   *     {@code IllegalArgumentException} is thrown if this option is
   *     specified with {@link TransactionDefinition#READ_ONLY}; Oracle
   *     Database does not support {@code SET TRANSACTION} commands that specify
   *     both isolation level and read only.
   *   </dd>
   *   <dt>{@link TransactionDefinition#READ_ONLY}</dt>
   *   <dd>
   *     Specifies a clause of {@code READ ONLY} if the value is {@code true},
   *     or {@code READ WRITE} if the value is {@code false}.
   *     {@code IllegalArgumentException} is thrown if this option is
   *     specified with {@link TransactionDefinition#ISOLATION_LEVEL}; Oracle
   *     Database does not support {@code SET TRANSACTION} commands that specify
   *     both isolation level and read only or read write.
   *   </dd>
   *   <dt>{@link TransactionDefinition#NAME}</dt>
   *   <dd>
   *     Specifies the argument to a NAME clause. If this option is specified
   *     without {@link TransactionDefinition#ISOLATION_LEVEL} or
   *     {@link TransactionDefinition#READ_ONLY}, the database begins a
   *     transaction having the default isolation level, READ COMMITTED, with
   *     the specified name.
   *   </dd>
   *   <dt>{@link TransactionDefinition#LOCK_WAIT_TIMEOUT}</dt>
   *   <dd>
   *     Not supported in this release of Oracle R2DBC. Oracle Database does
   *     not support {@code SET TRANSACTION} commands that specify a lock
   *     wait timeout.
   *   </dd>
   * </dl><p>
   * Any attribute that is not listed above is ignored and does not affect the
   * behavior of this method.
   * </p>
   *
   *
   * @implNote Supporting SERIALIZABLE isolation level requires a way to
   * disable Oracle JDBC's result set caching feature.
   *
   * @implNote Supporting {@code LOCK_WAIT_TIMEOUT} could be emulated by
   * executing {@code ALTER SESSION SET ddl_lock_wait=...}, and then resetting
   * the value once the transaction ends.
   *
   * @throws IllegalArgumentException If the {@code definition} specifies an
   * unsupported isolation level.
   * @throws IllegalArgumentException If the {@code definition} specifies both
   * an isolation level and read only.
   * @throws IllegalArgumentException If the {@code definition} does not
   * specify an isolation level, read only, or name.
   * @throws IllegalArgumentException If the {@code definition} specifies a
   * lock wait timeout.
   * @throws IllegalStateException If this {@code Connection} is closed
   */
  @Override
  public Publisher<Void> beginTransaction(TransactionDefinition definition) {
    requireOpenConnection(jdbcConnection);
    requireNonNull(definition, "definition is null");
    validateTransactionDefinition(definition);

    return Mono.from(setAutoCommit(false))
      .then(Mono.from(createStatement(composeSetTransaction(definition))
        .execute())
        .flatMap(result -> Mono.from(result.getRowsUpdated()))
        .then())
      .cache();
  }

  /**
   * Composes a {@code SET TRANSACTION} statement with the attributes
   * specified by a {@code definition}. The statement is composed as specified
   * in the javadoc of
   * {@link OracleConnectionImpl#beginTransaction(TransactionDefinition)}.
   * @param definition A transaction definition. Not null.
   * @return A SET TRANSACTION statement
   */
  private String composeSetTransaction(TransactionDefinition definition) {
    StringBuilder setTransactionBuilder = new StringBuilder("SET TRANSACTION");
    IsolationLevel isolationLevel = definition.getAttribute(ISOLATION_LEVEL);
    Boolean isReadOnly = definition.getAttribute(READ_ONLY);
    String name = definition.getAttribute(NAME);

    if (isolationLevel != null) {
      // Compose: SET TRANSACTION ISOLATION LEVEL ..."
      if (READ_COMMITTED.equals(isolationLevel)) {
        setTransactionBuilder.append(" ISOLATION LEVEL READ COMMITTED");
      }
      else if (SERIALIZABLE.equals(isolationLevel)) {
        setTransactionBuilder.append(" ISOLATION LEVEL SERIALIZABLE");
      }
      else {
        throw new IllegalArgumentException(
          "Unsupported isolation level:" + isolationLevel);
      }
    }
    else if (isReadOnly != null) {
      // Compose: SET TRANSACTION READ ..."
      setTransactionBuilder.append(isReadOnly ? " READ ONLY" : " READ WRITE");
    }

    if (name != null) {
      // Compose: SET TRANSACTION ... NAME ..."
      setTransactionBuilder.append(" NAME ")
        // Enquote the name to prevent any kind of SQL injection
        .append(enquoteLiteral(name));
    }

    return setTransactionBuilder.toString();
  }

  /**
   * Enquotes a literal value by invoking
   * {@link java.sql.Statement#enquoteLiteral(String)} on a {@code Statement}
   * created by the {@link #jdbcConnection}.
   * @param literal A literal value to enquote. Not null.
   * @return An enquoted form of the {@code literal} value.
   */
  private String enquoteLiteral(String literal) {
    try (var jdbcStatement = jdbcConnection.createStatement()) {
      return jdbcStatement.enquoteLiteral(literal);
    }
    catch (SQLException sqlException) {
      throw toR2dbcException(sqlException);
    }
  }

  /**
   * Validates the combination of attributes specified by a
   * {@code TransactionDefinition}. The validation is performed as specified
   * in the javadoc of
   * {@link OracleConnectionImpl#beginTransaction(TransactionDefinition)}.
   * @param definition {@code TransactionDefinition} to validate. Not null.
   * @throws IllegalArgumentException If the {@code definition} is not valid.
   */
  private static void validateTransactionDefinition(
    TransactionDefinition definition) {
    IsolationLevel isolationLevel = definition.getAttribute(ISOLATION_LEVEL);
    Boolean isReadOnly = definition.getAttribute(READ_ONLY);
    String name = definition.getAttribute(NAME);

    if (isolationLevel != null) {

      if (isReadOnly != null) {
        throw new IllegalArgumentException(
          "Specifying both ISOLATION_LEVEL and READ_ONLY is not supported");
      }

      // TODO: Only supporting READ COMMITTED
      if (! isolationLevel.equals(READ_COMMITTED)) {
        throw new IllegalArgumentException(
          "Unsupported ISOLATION_LEVEL: " + isolationLevel);
      }
    }
    else if (isReadOnly == null && name == null) {
      throw new IllegalArgumentException(
        "Transaction definition does not specify an isolation level, read " +
          "only, or name. At least one must be specified.");
    }

    if (definition.getAttribute(LOCK_WAIT_TIMEOUT) != null) {
      // TODO: ALTER SESSION SET ddl_lock_wait = ...
      throw new IllegalArgumentException(
        "LOCK_WAIT_TIMEOUT is not supported in this release");
    }
  }

  /**
   * {@inheritDoc}
   * <p>
   * Implements the R2DBC SPI method by closing the JDBC connection.
   * </p><p>
   * Publishers emit {@code onError} with an {@link IllegalStateException} when
   * interacting with a closed connection or with any objects created by a
   * closed connection .
   * </p><p>
   * The returned publisher closes the connection <i>after</i> a subscriber
   * subscribes, <i>before</i> the subscriber emits a {@code request}
   * signal. Multiple subscribers are supported, but the returned publisher
   * does not repeat the action of closing the connection for each
   * subscription. Signals emitted to the first subscription are propagated
   * to all subsequent subscriptions.
   * </p><p>
   * Calling this method on a Connection that is already closed is a no-op.
   * The returned publisher emits {@code onComplete} if the connection is
   * already closed.
   * </p>
   */
  @Override
  public Publisher<Void> close() {
    return adapter.publishClose(jdbcConnection);
  }

  /**
   * {@inheritDoc}
   * <p>
   * Implements the R2DBC SPI method by committing a transaction on the
   * Oracle Database to which JDBC is connected.
   * </p><p>
   * The returned publisher commits the transaction <i>after</i> a
   * subscriber subscribes, <i>before</i> the subscriber emits a {@code
   * request} signal. Multiple subscribers are supported, but the returned
   * publisher does not repeat the action of committing the transaction for
   * each subscription. Signals emitted to the first subscription are
   * propagated to all subsequent subscriptions.
   * </p><p>
   * Calling this method is a no-op if auto-commit is enabled. The returned
   * publisher emits {@code onComplete} if auto-commit is enabled.
   * </p>
   * @throws IllegalStateException If this {@code Connection} is closed
   */
  @Override
  public Publisher<Void> commitTransaction() {
    requireOpenConnection(jdbcConnection);
    return adapter.publishCommit(jdbcConnection);
  }

  /**
   * {@inheritDoc}
   * <p>
   * Implements the R2DBC SPI method by returning a {@code Batch} that executes
   * a sequence of arbitrary SQL statements on the Oracle Database to which JDBC
   * is connected.
   * </p><p>
   * Parallel execution of {@code Batch} objects created by a single {@code
   * Connection} is <i>not</i> supported by the Oracle R2DBC Driver. The
   * Oracle R2DBC Driver reflects the capabilities of Oracle Database, which
   * does <i>not</i> support parallel execution of SQL within a single
   * database session. Attempting parallel execution of {@code Batch} objects
   * from the same {@code Connection} will cause threads to become blocked as
   * each SQL command executes serially.
   * </p>
   * @throws IllegalStateException If this {@code Connection} is closed
   */
  @Override
  public Batch createBatch() {
    requireOpenConnection(jdbcConnection);
    return new OracleBatchImpl(adapter, jdbcConnection);
  }

  /**
   * {@inheritDoc}
   * <p>
   * Implements the R2DBC SPI method by returning a new statement that is
   * executed by the Oracle Database to which JDBC is connected.
   * </p><p>
   * Parallel execution of {@code Statement} objects created by a single
   * {@code Connection} is <i>not</i> supported by the Oracle R2DBC Driver.
   * The Oracle R2DBC Driver reflects the capabilities of Oracle Database, which
   * does <i>not</i> support parallel execution of SQL within a single
   * database session. Attempting parallel execution of {@code Statement}
   * objects from the same {@code Connection} will cause threads to become
   * blocked as each statement executes serially.
   * </p>
   *
   * @throws IllegalStateException If this {@code Connection} is closed
   */
  @Override
  public Statement createStatement(String sql) {
    requireNonNull(sql, "sql is null");
    requireOpenConnection(jdbcConnection);
    return new OracleStatementImpl(adapter, jdbcConnection, sql);
  }

  /**
   * {@inheritDoc}
   * <p>
   * Implements the R2DBC SPI method by returning the current auto-commit mode
   * of the JDBC connection.
   * </p>
   * @throws IllegalStateException If this {@code Connection} is closed
   */
  @Override
  public boolean isAutoCommit() {
    requireOpenConnection(jdbcConnection);
    return getOrHandleSQLException(jdbcConnection::getAutoCommit);
  }

  /**
   * {@inheritDoc}
   * <p>
   * Implements the R2DBC SPI method by returning metadata about the
   * Oracle Database to which JDBC is connected.
   * </p>
   * @throws IllegalStateException If this {@code Connection} is closed
   */
  @Override
  public ConnectionMetadata getMetadata() {
    requireOpenConnection(jdbcConnection);
    return new OracleConnectionMetadataImpl(
      getOrHandleSQLException(jdbcConnection::getMetaData));
  }

  /**
   * {@inheritDoc}
   * <p>
   * This SPI method is not yet implemented.
   * </p>
   * @throws UnsupportedOperationException In this release of Oracle
   * R2DBC
   * @throws IllegalStateException If this {@code Connection} is closed
   */
  @Override
  public Publisher<Void> createSavepoint(String name) {
    requireNonNull(name, "name is null");
    requireOpenConnection(jdbcConnection);
    // TODO: Execute SQL to create a savepoint. Examine and understand the
    // Oracle JDBC driver's implementation of
    // OracleConnection.oracleSetSavepoint(), and replicate it without
    // blocking a thread. Consider adding a ReactiveJDBCAdapter API to do this.
    throw new UnsupportedOperationException("createSavepoint not supported");
  }

  /**
   * {@inheritDoc}
   * <p>
   * Implements the R2DBC SPI method as a no-op. Oracle Database does not
   * support explicit releasing of savepoints.
   * </p>
   * @throws IllegalStateException If this {@code Connection} is closed
   */
  @Override
  public Publisher<Void> releaseSavepoint(String name) {
    requireNonNull(name, "name is null");
    requireOpenConnection(jdbcConnection);
    return Mono.empty();
  }

  /**
   * {@inheritDoc}
   * <p>
   * Implements the R2DBC SPI method by rolling back a transaction on the
   * Oracle Database to which JDBC is connected.
   * </p><p>
   * The returned publisher rolls back the current transaction <i>after</i>
   * a subscriber subscribes, <i>before</i> the subscriber emits a {@code
   * request} signal. Multiple subscribers are supported, but the returned
   * publisher does not repeat the action of rolling back the transaction for
   * each subscription. Signals emitted to the first subscription are
   * propagated to all subsequent subscriptions.
   * </p><p>
   * Calling this method is a no-op if auto-commit is enabled. The returned
   * publisher emits {@code onComplete} if auto-commit is enabled.
   * </p>
   * @throws IllegalStateException If this {@code Connection} is closed
   */
  @Override
  public Publisher<Void> rollbackTransaction() {
    requireOpenConnection(jdbcConnection);
    return adapter.publishRollback(jdbcConnection);
  }

  /**
   * {@inheritDoc}
   * <p>
   * This SPI method is not yet implemented.
   * </p>
   * @throws IllegalStateException If this {@code Connection} is closed
   * @throws UnsupportedOperationException In version this release of Oracle
   * R2DBC
   */
  @Override
  public Publisher<Void> rollbackTransactionToSavepoint(String name) {
    requireNonNull(name, "name is null");
    requireOpenConnection(jdbcConnection);
    // TODO: Use the JDBC connection to rollback to a savepoint without blocking
    // a thread.
    throw new UnsupportedOperationException(
      "rollbackTransactionToSavepoint not supported");
  }

  /**
   * {@inheritDoc}
   * <p>
   * This SPI method implementation sets the auto-commit mode of the JDBC
   * connection.
   * </p><p>
   * The returned publisher sets the JDBC connection's auto-commit mode
   * <i>after</i> a subscriber subscribes, <i>before</i> the subscriber
   * emits a {@code request} signal. Multiple subscribers are supported, but
   * the returned publisher does not repeat the action of setting the
   * auto-commit mode for each subscription. Signals emitted to the first
   * subscription are propagated to all subsequent subscriptions.
   * </p>
   * @throws IllegalStateException If this {@code Connection} is closed
   */
  @Override
  public Publisher<Void> setAutoCommit(boolean autoCommit) {
    requireOpenConnection(jdbcConnection);
    return Mono.defer(() -> getOrHandleSQLException(() -> {
      if (autoCommit == jdbcConnection.getAutoCommit()) {
        return Mono.empty(); // No change
      }
      else if (! autoCommit) {
        // Changing auto-commit from enabled to disabled. When enabled,
        // there is no active transaction.
        jdbcConnection.setAutoCommit(false);
        return Mono.empty();
      }
      else {
        // Changing auto-commit from disabled to enabled. Commit in case
        // there is an active transaction.
        return Mono.from(commitTransaction())
          .doOnSuccess(nil -> runOrHandleSQLException(() ->
            jdbcConnection.setAutoCommit(true)));
      }
    }))
    .cache();
  }

  /**
   * {@inheritDoc}
   * <p>
   * Implements the R2DBC SPI method by returning the JDBC connection's
   * transaction isolation level.
   * </p>
   * @implNote Currently, Oracle R2DBC only supports the READ COMMITTED
   * isolation level.
   * @throws IllegalStateException If this {@code Connection} is closed
   */
  @Override
  public IsolationLevel getTransactionIsolationLevel() {
    requireOpenConnection(jdbcConnection);
    return READ_COMMITTED;
  }

  /**
   * {@inheritDoc}
   * <p>
   * Implements the R2DBC SPI method by setting the transaction isolation
   * level of the JDBC connection.
   * </p><p>
   * Oracle Database only supports {@link IsolationLevel#READ_COMMITTED} and
   * {@link IsolationLevel#SERIALIZABLE} isolation levels. If an unsupported
   * {@code isolationLevel} is specified to this method, then the returned
   * publisher emits {@code onError} with an {@link R2dbcException}
   * indicating that the specified {@code isolationLevel} is not supported.
   * </p><p>
   * Oracle Database does not support changing an isolation level during
   * an active transaction. If the isolation level is changed during an
   * active transaction, then the returned publisher emits {@code onError}
   * with an {@link R2dbcException} indicating that changing the isolation level
   * during an active transaction is not supported.
   * </p><p>
   * The returned publisher sets the transaction isolation level
   * <i>after</i> a subscriber subscribes, <i>before</i> the subscriber
   * emits a {@code request} signal. Multiple subscribers are supported, but
   * the returned publisher does not repeat the action of setting the
   * transaction isolation level for each subscription. Signals emitted to
   * the first subscription are propagated to all subsequent subscriptions.
   * </p>
   * @implNote Currently, Oracle R2DBC only supports the READ COMMITTED
   * isolation level.
   * @throws IllegalStateException If this {@code Connection} is closed
   */
  @Override
  public Publisher<Void> setTransactionIsolationLevel(
    IsolationLevel isolationLevel) {
    requireNonNull(isolationLevel, "isolationLevel is null");
    requireOpenConnection(jdbcConnection);

    // TODO: Need to add a connection factory option that disables Oracle
    //  JDBC's Result Set caching function before SERIALIZABLE can be supported.
    // For now, the isolation level can never be changed from the default READ
    // COMMITTED.
    if (isolationLevel.equals(READ_COMMITTED)) {
      return Mono.empty();
    }
    else {
      return Mono.error(OracleR2dbcExceptions.newNonTransientException(
        "Oracle R2DBC does not support isolation level: " + isolationLevel,
        null));
    }
  }

  /**
   * {@inheritDoc}
   * <p>
   * Implements the R2DBC SPI method by validating the JDBC connection in one
   * of two ways, either locally or remotely, as specified by the {@code
   * depth} parameter. Local validation tests if the JDBC connection has
   * become closed, and remote validation tests if the JDBC connection can
   * execute a SQL statement.
   * </p><p>
   * The returned publisher validates the connection <i>after</i> a
   * subscriber subscribes, <i>before</i> the subscriber emits a {@code
   * request} signal. Multiple subscribers are supported, but the returned
   * publisher does not repeat the action of validating the connection for each
   * subscription. Signals emitted to the first subscription are
   * propagated to all subsequent subscriptions.
   * </p>
   * @implNote Remote validation executes a SQL query against the {@code sys
   * .dual} table. It is assumed that all Oracle Databases have the {@code
   * sys.dual} table.
   */
  @Override
  public Publisher<Boolean> validate(ValidationDepth depth) {
    requireNonNull(depth, "depth is null");
    return Mono.defer(() -> getOrHandleSQLException(() -> {
      if (jdbcConnection.isClosed()) {
        return Mono.just(false);
      }
      else if (depth == ValidationDepth.LOCAL) {
        return Mono.just(true);
      }
      else {
        return Mono.from(createStatement("SELECT 1 FROM sys.dual")
          .execute())
          .flatMap(result ->
            Mono.from(result.map((row, metadata) ->
              row.get(0, Integer.class))))
          .map(value -> Integer.valueOf(1).equals(value))
          .defaultIfEmpty(false)
          .onErrorReturn(false);
      }
    }))
    .cache();
  }

}