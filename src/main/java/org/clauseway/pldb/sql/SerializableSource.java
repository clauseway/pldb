package org.clauseway.pldb.sql;

// ABOUTME: Native serialization over JDBC: a source under honest SERIALIZABLE
// ABOUTME: isolation, its backend's conflict dialect recognized at the commit door.

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.function.Predicate;
import org.clauseway.logic.solving.Call;
import org.clauseway.logic.solving.Answer;
import org.clauseway.pldb.relations.Literal;
import org.clauseway.pldb.relations.Relation;
import org.clauseway.pldb.transaction.NativeSerialization;

/**
 * A pinned source whose backend certifies reads itself: the connection
 * is granted SERIALIZABLE before pinning, and the factory that mints
 * this wrapper VOUCHES that the backend's SERIALIZABLE actually
 * validates read sets (PostgreSQL's SSI, two-phase-locking
 * implementations) and speaks its conflict dialect. A backend whose
 * SERIALIZABLE is snapshot isolation in costume must not come through
 * here — no dialect predicate can make it honest.
 */
public final class SerializableSource implements JdbcSource, NativeSerialization {

	private final CachingSqlFetch inner;
	private final Predicate<SQLException> dialect;

	private SerializableSource(CachingSqlFetch inner, Predicate<SQLException> dialect) {
		this.inner = inner;
		this.dialect = dialect;
	}

	/** PostgreSQL: SSI, refusals as the standard serialization-failure SQLSTATE. */
	public static SerializableSource postgres(String id, Connection connection) {
		return pinned(id, connection, e -> "40001".equals(e.getSQLState()));
	}

	public static SerializableSource pinned(String id, Connection connection,
			Predicate<SQLException> dialect) {
		try {
			connection.setAutoCommit(false);
			connection.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
		} catch (SQLException e) {
			throw new IllegalStateException("could not open " + id + " serializable", e);
		}
		return new SerializableSource(CachingSqlFetch.pinned(id, connection), dialect);
	}

	/**
	 * The rented commit door: flush and commit on the snapshot's own
	 * transaction — the backend has been tracking every read it served,
	 * and a refusal in this source's dialect means the world moved.
	 */
	@Override
	public boolean commit(List<Answer<Relation>> asserted, List<Answer<Relation>> retracted) {
		try {
			SqlFlush door = SqlFlush.over(getConnection(), inner.codecs());
			door.flush(asserted);
			door.delete(retracted);
			getConnection().commit();
			return true;
		} catch (RuntimeException e) {
			rollBackQuietly();
			if (recognized(e)) {
				return false;
			}
			throw e;
		} catch (SQLException e) {
			rollBackQuietly();
			if (recognized(e)) {
				return false;
			}
			throw new IllegalStateException(id() + ": commit failed", e);
		}
	}

	private boolean recognized(Throwable e) {
		for (Throwable cause = e; cause != null; cause = cause.getCause()) {
			if (cause instanceof SQLException && dialect.test((SQLException) cause)) {
				return true;
			}
		}
		return false;
	}

	private void rollBackQuietly() {
		try {
			getConnection().rollback();
		} catch (SQLException suppressed) {
			// the transaction is already dead; the caller gets the original failure
		}
	}

	@Override
	public Iterable<Answer<Relation>> answers(Call<Relation> probe) {
		return inner.answers(probe);
	}

	@Override
	public long estimate(Call<Relation> probe) {
		return inner.estimate(probe);
	}

	@Override
	public String id() {
		return inner.id();
	}

	@Override
	public void close() throws Exception {
		inner.close();
	}

	@Override
	public Connection getConnection() {
		return inner.getConnection();
	}

	@Override
	public Codecs codecs() {
		return inner.codecs();
	}

	/** Binds column codecs through a template literal. Before first use only. */
	public SerializableSource withCodec(Literal template) {
		inner.withCodec(template);
		return this;
	}
}
