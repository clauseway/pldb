package org.clauseway.pldb.sql;

// ABOUTME: Region-grain simulated serialization: a monotone version column per
// ABOUTME: table, the region pin is (MAX(version), COUNT(*)) — inserts move the
// ABOUTME: MAX, deletes move the COUNT, and the pair can never be restored.

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import lombok.AllArgsConstructor;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.clauseway.logic.solving.Call;
import org.clauseway.logic.solving.Answer;
import org.clauseway.pldb.relations.Relation;
import org.clauseway.pldb.transaction.Footprint;
import org.clauseway.pldb.transaction.Pin;
import org.clauseway.pldb.transaction.Pinned;
import org.clauseway.pldb.transaction.SimulatedSerialization;

/**
 * Simulated serialization at REGION grain: every table carries a
 * monotone {@code version} column (the backend's private surface —
 * relations never declare it, the fetch never selects it, the flush
 * stamps it), and a region's pin is the PAIR
 * {@code (MAX(version), COUNT(*))} over the rows matching the probe —
 * {@code (null, 0)} for an empty region. The certify re-evaluates the
 * same pair under the commit lock through the SAME rendering the fetch
 * reads with ({@link RegionSql}): reading narrowly and proving narrowly
 * are one spelling. Stamps come from the {@code watermark} lock row
 * ({@link Watermark#schema}), bumped per commit, so versions are
 * monotone across the whole source. The pair is why every write shape
 * is visible: an INSERT into a pinned region always moves its MAX
 * (phantoms, which row-grain optimistic locking cannot see), a DELETE
 * always drops its COUNT, a delete-plus-insert moves the MAX again —
 * and the pair can never be RESTORED, because restoring the MAX would
 * reuse a stamp the monotone counter never re-issues, and restoring
 * the COUNT takes an insert, which moves the MAX.
 *
 * <p>The trust boundary that remains: an in-place UPDATE that keeps
 * its version moves a region without moving either component — writes
 * change rows only via stamped insert (and, for compaction, delete),
 * the same protocol-abiding rent every simulated kind pays. Every
 * table this kind writes must have the version column; a table
 * without one refuses loudly at its first read.
 */
@Slf4j
@Value
@AllArgsConstructor
public class VersionedWatermark implements JdbcSource, SimulatedSerialization {
	private static final String LOCK_ROW = "*";
	private static final String VERSION_COLUMN = "version";

	SqlFetch source;
	Supplier<Connection> commits;

	public static VersionedWatermark over(SqlFetch source, Supplier<Connection> commits) {
		return new VersionedWatermark(source, commits);
	}

	/**
	 * One region's (MAX(version), COUNT(*)) as of its read; {@code (null, 0)}
	 * = empty region. Inserts move the max, deletes move the count, and no
	 * write sequence restores the pair.
	 */
	@Value
	private static class RegionPin implements Pin {
		Long max;
		long count;
	}

	/**
	 * Pin BEFORE rows, and the rows RAW — beneath the source's shared
	 * cache — so pin and data are minted from one world; on the live
	 * lane both orderings are load-bearing.
	 */
	@Override
	public Pinned<Iterable<Answer<Relation>>> read(Call<Relation> probe) {
		Pin pin = regionPin(source.getConnection(), probe);
		return Pinned.of(source.answers(probe), pin);
	}

	@Override
	public boolean commit(Footprint read, List<Answer<Relation>> asserted, List<Answer<Relation>> retracted) {
		try (Connection commit = commits.get()) {
			commit.setAutoCommit(false);
			try {
				long stamp = lockMark(commit) + 1;
				log.debug("{}: commit lock taken, stamp {}", id(), stamp);
				for (Map.Entry<Call<Relation>, Pin> pinned : read.pins().entrySet()) {
					RegionPin current = regionPin(commit, pinned.getKey());
					if (!Objects.equals(current, pinned.getValue())) {
						log.debug("{}: region {}{} moved — pinned {}, current {}", id(),
								pinned.getKey().getRelation().getName(), pinned.getKey().getArguments(),
								pinned.getValue(), current);
						commit.rollback();
						return false;
					}
				}
				SqlFlush door = SqlFlush.over(commit, source.getCodecs());
				door.stamped(VERSION_COLUMN, stamp).flush(asserted);
				door.delete(retracted);
				bumpLock(commit);
				commit.commit();
				return true;
			} catch (SQLException | RuntimeException e) {
				commit.rollback();
				throw e;
			}
		} catch (SQLException e) {
			throw new IllegalStateException(id() + ": versioned serialization failed", e);
		}
	}

	/**
	 * The pin statement joins the fetch's monitor: one connection, one
	 * monitor — a ForkJoin solve's concurrent reads serialize here. The
	 * commit lane's calls ride the same monitor harmlessly (its own
	 * connection is private; reads never touch the DB lock row, so the
	 * monitor→lock order cannot invert).
	 */
	private RegionPin regionPin(Connection connection, Call<Relation> probe) {
		synchronized (source) {
			return readRegionPin(connection, probe);
		}
	}

	private RegionPin readRegionPin(Connection connection, Call<Relation> probe) {
		RegionSql region = source.region(probe);
		String sql = "SELECT MAX(" + VERSION_COLUMN + "), COUNT(*) FROM "
				+ probe.getRelation().getName() + region.whereClause();
		try (PreparedStatement statement = connection.prepareStatement(sql)) {
			int index = 1;
			for (Object parameter : region.getParameters()) {
				statement.setObject(index++, parameter);
			}
			try (ResultSet row = statement.executeQuery()) {
				row.next();
				long value = row.getLong(1);
				Long max = row.wasNull() ? null : value;
				RegionPin pin = new RegionPin(max, row.getLong(2));
				log.debug("{}: {} ← {} = {}", id(), sql, region.getParameters(), pin);
				return pin;
			}
		} catch (SQLException e) {
			throw new IllegalStateException(id() + ": could not read MAX(" + VERSION_COLUMN
					+ ") of " + probe.getRelation().getName()
					+ " — the versioned kind requires a '" + VERSION_COLUMN + "' column", e);
		}
	}

	/** The lock row FOR UPDATE — the commit lock and the stamp sequence. */
	private static long lockMark(Connection commit) throws SQLException {
		try (
				PreparedStatement lock = commit.prepareStatement(
						"SELECT mark FROM watermark WHERE relation = ? FOR UPDATE")
		) {
			lock.setString(1, LOCK_ROW);
			try (ResultSet row = lock.executeQuery()) {
				if (!row.next()) {
					throw new SQLException("watermark lock row missing — run Watermark.schema first");
				}
				return row.getLong(1);
			}
		}
	}

	private static void bumpLock(Connection commit) throws SQLException {
		try (
				PreparedStatement update = commit.prepareStatement(
						"UPDATE watermark SET mark = mark + 1 WHERE relation = ?")
		) {
			update.setString(1, LOCK_ROW);
			update.executeUpdate();
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
	public Iterable<Answer<Relation>> answers(Call<Relation> probe) {
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

	@Override
	public void close() throws Exception {
		source.close();
	}
}
