package org.clauseway.pldb.sql;

// ABOUTME: The JDBC-backed AnswerSource: a constructor and a wrapper — the caching
// ABOUTME: source over the pinned SQL fetch, plus the registration and close doors.

import java.sql.Connection;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.Value;
import org.clauseway.logic.solving.Call;
import org.clauseway.pldb.relations.Answer;
import org.clauseway.pldb.relations.Literal;
import org.clauseway.pldb.relations.Relation;

/**
 * A relation backend over one pinned JDBC connection, by CONVENTION: the
 * database schema matches the pldb schema, so the relation's name is the
 * table and its property names are the columns — no mapping layer, and
 * values must be JDBC-representable; columns backing relation properties
 * are NON-NULL (SQL's three-valued logic would silently drop NULL rows
 * from pushed comparisons — under-delivery — while the engine has no null
 * vocabulary at all). The backend is the schema authority: a relation
 * without a table fails loudly at its first fetch.
 *
 * <p>This class is a constructor and a wrapper: {@link CachingAnswerSource}
 * over the pinned {@link SqlFetch}. The fetch owns the backend — the
 * connection, the per-family compiler registry, probe+region compiled to
 * SELECT..WHERE, every get a round trip. The cache owns reuse — landing,
 * the coverage ledger, containment proof. What remains here is the
 * lifecycle the composition needs: pinning at construction, compiler
 * registration before first use, and {@link #close()} rolling the
 * transaction back. Estimates are exact over covered probes and the
 * optimizer barrier otherwise — never a remote round trip.
 *
 * <p>The connection is shared and the composed get synchronized: parallel
 * solves serialize their fetches here. Landed answers are immutable
 * snapshots, so reads outside the monitor stay safe.
 */
@Value
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public class CachingSqlFetch implements JdbcSource {

	SqlFetch fetch;
	CachingAnswerSource cached;

	/**
	 * Pins the connection and wires the ENGINE-CORE compilers as equipment —
	 * the FD family pushes out of the box. {@link #compiling} registers user
	 * families and may OVERRIDE a built-in.
	 */
	public static CachingSqlFetch pinned(String id, Connection connection) {
		return composed(SqlFetch.pinned(id, connection));
	}

	/**
	 * The LIVE lane: reads the current committed world per statement, no
	 * snapshot held — the caching ledger is what keeps a solve's repeats
	 * stable, and a transaction over this lane leans wholly on per-touch
	 * pins and the commit proof.
	 */
	public static CachingSqlFetch live(String id, Connection connection) {
		return composed(SqlFetch.live(id, connection));
	}

	private static CachingSqlFetch composed(SqlFetch fetch) {
		return new CachingSqlFetch(fetch, CachingAnswerSource.over(fetch));
	}

	/**
	 * Registers the family's WHERE compiler. Before first use only — a
	 * registry changing under live coverage would make containment
	 * order-dependent.
	 */
	public CachingSqlFetch compiling(Class<?> family, SqlCompiler compiler) {
		if (!cached.isEmpty()) {
			throw new IllegalStateException(id() + ": register compilers before first use");
		}
		fetch.compiling(family, compiler);
		return this;
	}

	/** Binds column codecs through a template literal. Before first use only. */
	public CachingSqlFetch withCodec(Literal template) {
		if (!cached.isEmpty()) {
			throw new IllegalStateException(id() + ": register codecs before first use");
		}
		fetch.getCodecs().withCodec(template);
		return this;
	}

	@Override
	public Codecs codecs() {
		return fetch.getCodecs();
	}

	/** The probe's region rendered as SQL — the certify side's door. */
	RegionSql region(Call<Relation> probe) {
		return fetch.region(probe);
	}

	@Override
	public String id() {
		return fetch.id();
	}

	/** The isolation level the backend actually granted — the pin's declared capability. */
	public int isolation() {
		return fetch.isolation();
	}

	@Override
	public Iterable<Answer> answers(Call<Relation> probe) {
		return cached.answers(probe);
	}

	@Override
	public long estimate(Call<Relation> probe) {
		return cached.estimate(probe);
	}

	@Override
	public String toString() {
		return cached.toString();
	}

	@Override
	public Connection getConnection() {
		return fetch.getConnection();
	}

	@Override
	public void close() throws Exception {
		fetch.close();
	}
}
