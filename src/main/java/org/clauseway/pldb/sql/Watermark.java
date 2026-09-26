package org.clauseway.pldb.sql;

// ABOUTME: Simulated serialization over standard SQL: per-relation marks in one
// ABOUTME: private table; commit = one short lock-compare-flush-advance transaction.

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import lombok.AllArgsConstructor;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.clauseway.logic.solving.Call;
import org.clauseway.pldb.relations.Answer;
import org.clauseway.pldb.relations.Relation;
import org.clauseway.pldb.transaction.Footprint;
import org.clauseway.pldb.transaction.Pin;
import org.clauseway.pldb.transaction.Pinned;
import org.clauseway.pldb.transaction.SimulatedSerialization;

/**
 * Equips a source with SIMULATED serialization in plain standard SQL: a
 * private {@code watermark(relation, mark)} table with one lock row.
 * {@link #read} answers a probe WITH its region's mark, mark captured
 * first through the snapshot connection — the snapshot makes the
 * ordering moot. {@link #commit} runs on a FRESH connection from the
 * supplier — a snapshot cannot see the current world, and the proof is
 * exactly a question about the current world: lock the lock row
 * {@code FOR UPDATE} (serializing committers), compare every footprint
 * pin against the current marks, land the facts, bump the moved
 * relations, commit — one transaction, atomic. A mark row is minted by
 * a relation's first write, so a relation absent on BOTH sides of the
 * comparison is proven unmoved — double absence is silence, not
 * blindness. {@link #schema} is the DDL door — run it before any
 * transaction opens.
 */
@Slf4j
@Value
@AllArgsConstructor
public class Watermark implements JdbcSource, SimulatedSerialization {
	private static final String LOCK_ROW = "*";

	SqlFetch source;
	Supplier<Connection> commits;

	public static Watermark over(SqlFetch source, Supplier<Connection> commits) {
		return new Watermark(source, commits);
	}

	/** The watermark table and its lock row; idempotent, admin-connection DDL. */
	public static void schema(Statement admin) throws SQLException {
		admin.execute("CREATE TABLE IF NOT EXISTS watermark("
				+ "relation VARCHAR(128) PRIMARY KEY, mark BIGINT NOT NULL)");
		try (
				ResultSet lockRow = admin.executeQuery(
						"SELECT 1 FROM watermark WHERE relation = '" + LOCK_ROW + "'")
		) {
			if (!lockRow.next()) {
				admin.execute("INSERT INTO watermark(relation, mark) VALUES ('" + LOCK_ROW + "', 0)");
			}
		}
	}

	@Override
	public Connection getConnection() {
		return source.getConnection();
	}

	@Override
	public Codecs codecs() {
		return source.getCodecs();
	}

	@Override
	public void close() throws Exception {
		source.close();
	}

	/** One region's mark as of its first read; {@code null} = no row = never written. */
	@Value
	private static class Mark implements Pin {
		Long mark;
	}

	/**
	 * Mark BEFORE rows, and the rows RAW — beneath the source's shared
	 * cache — so pin and data are minted from one world: a cached row
	 * from an earlier reader beside a fresh mark would be data the pin
	 * never named.
	 */
	@Override
	public Pinned<Iterable<Answer>> read(Call<Relation> probe) {
		Pin mark = markOf(probe.getRelation().getName());
		return Pinned.of(source.answers(probe), mark);
	}

	/**
	 * The pin statement joins the fetch's monitor: one connection, one
	 * monitor — a ForkJoin solve's concurrent reads serialize here.
	 */
	private Pin markOf(String relation) {
		synchronized (source) {
			return readMark(relation);
		}
	}

	private Pin readMark(String relation) {
		try (
				PreparedStatement read = source.getConnection().prepareStatement(
						"SELECT mark FROM watermark WHERE relation = ?")
		) {
			read.setString(1, relation);
			try (ResultSet row = read.executeQuery()) {
				Mark mark = new Mark(row.next() ? row.getLong(1) : null);
				log.debug("{}: pin {} = {}", id(), relation, mark.getMark());
				return mark;
			}
		} catch (SQLException e) {
			throw new IllegalStateException(id() + ": could not read the watermark", e);
		}
	}

	@Override
	public boolean commit(Footprint read, List<Answer> asserted, List<Answer> retracted) {
		try (Connection commit = commits.get()) {
			commit.setAutoCommit(false);
			try {
				Map<String, Long> current = lockAndReadMarks(commit);
				if (!covers(current, read)) {
					commit.rollback();
					return false;
				}
				SqlFlush flush = SqlFlush.over(commit, source.getCodecs());
				flush.flush(asserted);
				flush.delete(retracted);
				List<Answer> moved = new ArrayList<>(asserted);
				moved.addAll(retracted);
				advance(commit, moved);
				commit.commit();
				return true;
			} catch (SQLException | RuntimeException e) {
				commit.rollback();
				throw e;
			}
		} catch (SQLException e) {
			throw new IllegalStateException(id() + ": simulated serialization failed", e);
		}
	}

	/** The lock row first (the commit lock), then every mark, all current. */
	private Map<String, Long> lockAndReadMarks(Connection commit) throws SQLException {
		Map<String, Long> current = new HashMap<>();
		try (
				PreparedStatement lock = commit.prepareStatement(
						"SELECT relation, mark FROM watermark WHERE relation = ? FOR UPDATE")
		) {
			lock.setString(1, LOCK_ROW);
			try (ResultSet row = lock.executeQuery()) {
				if (!row.next()) {
					throw new SQLException("watermark lock row missing — run Watermark.schema first");
				}
				current.put(LOCK_ROW, row.getLong(2));
			}
		}
		try (
				PreparedStatement read = commit.prepareStatement(
						"SELECT relation, mark FROM watermark");
				ResultSet rows = read.executeQuery()
		) {
			while (rows.next()) {
				current.put(rows.getString(1), rows.getLong(2));
			}
		}
		log.debug("{}: commit lock taken, marks {}", id(), current);
		return current;
	}

	private boolean covers(Map<String, Long> current, Footprint read) {
		for (Map.Entry<Call<Relation>, Pin> pinned : read.pins().entrySet()) {
			String relation = pinned.getKey().getRelation().getName();
			if (!Objects.equals(current.get(relation), ((Mark) pinned.getValue()).getMark())) {
				log.debug("{}: {} moved — pinned {}, current {}", id(), relation,
						((Mark) pinned.getValue()).getMark(), current.get(relation));
				return false;
			}
		}
		return true;
	}

	private void advance(Connection commit, List<Answer> flushed) throws SQLException {
		Set<String> moved = new LinkedHashSet<>();
		moved.add(LOCK_ROW);
		for (Answer row : flushed) {
			moved.add(row.getRelation().getName());
		}
		log.debug("{}: advance {}", id(), moved);
		for (String relation : moved) {
			bump(commit, relation);
		}
	}

	private static void bump(Connection commit, String relation) throws SQLException {
		try (
				PreparedStatement update = commit.prepareStatement(
						"UPDATE watermark SET mark = mark + 1 WHERE relation = ?")
		) {
			update.setString(1, relation);
			if (update.executeUpdate() == 0) {
				try (
						PreparedStatement insert = commit.prepareStatement(
								"INSERT INTO watermark(relation, mark) VALUES (?, 1)")
				) {
					insert.setString(1, relation);
					insert.executeUpdate();
				}
			}
		}
	}

	@Override
	public Iterable<Answer> answers(Call<Relation> probe) {
		return source.answers(probe);
	}

	@Override
	public long estimate(Call<Relation> probe) {
		return source.estimate(probe);
	}

	@Override
	public String id() {
		return source.id();
	}
}
