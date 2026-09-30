package org.clauseway.pldb.sql;

// ABOUTME: Pins the SQL adapter: an H2-backed AnswerSource answers identically to
// ABOUTME: the in-memory reference, refuses unserved relations, and lands fetches
// ABOUTME: so subsumed probes never touch the backend again.

import org.clauseway.logic.solving.Query;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.clauseway.logic.unification.terms.LVal.lval;
import static org.clauseway.logic.unification.terms.LVar.lvar;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.clauseway.logic.unification.terms.Unifiable;
import org.clauseway.pldb.AnswerSource;
import org.clauseway.pldb.inmemory.AnswerStore;
import org.clauseway.pldb.relations.Literal;
import org.clauseway.pldb.relations.Property;
import org.clauseway.pldb.relations.Relation;
import org.clauseway.pldb.relations.RelationN;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class CachingSqlFetchTest {

	private static Literal person(AnswerSource db, Unifiable<Integer> id, Unifiable<String> name) {
		return Literal.relation(CachingSqlFetchTest.class, "person")
				.arg("id", id).indexed()
				.arg("name", name)
				.from(db);
	}

	private static Relation personRel() {
		return person(null, lvar(), lvar()).getRel();
	}

	private static final Property<Integer> id = Property.of("id");
	private static final Property<String> name = Property.of("name");

	private static final AnswerStore reference = AnswerStore.empty()
			.asserting(Arrays.asList(
					person(null, lval(1), lval("Ada")),
					person(null, lval(2), lval("Alan")),
					person(null, lval(3), lval("Kurt"))));

	private Connection connection;
	private final AtomicInteger statements = new AtomicInteger();

	@Before
	public void loadH2() throws SQLException {
		connection = DriverManager.getConnection("jdbc:h2:mem:");
		try (Statement ddl = connection.createStatement()) {
			ddl.execute("CREATE TABLE person(id INT, name VARCHAR(64))");
			ddl.execute("INSERT INTO person VALUES (1, 'Ada'), (2, 'Alan'), (3, 'Kurt')");
		}
	}

	@After
	public void closeH2() throws SQLException {
		connection.close();
	}

	private CachingSqlFetch source() {
		return CachingSqlFetch.pinned("h2-test", counting(connection));
	}

	@Test
	public void pinningKeepsAStrongerIsolationLevel() throws Exception {
		// the pin promises AT LEAST a repeatable snapshot; a caller that
		// already granted SERIALIZABLE (the rented certify) must keep it
		connection.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
		try (CachingSqlFetch pinned = CachingSqlFetch.pinned("h2-serializable", connection)) {
			assertThat(pinned.isolation()).isEqualTo(Connection.TRANSACTION_SERIALIZABLE);
		}
	}

	@Test
	public void answersLikeTheInMemoryReference() throws Exception {
		try (CachingSqlFetch source = source()) {
			assertThat(solvedNames(source)).isEqualTo(solvedNames(reference));
		}
	}

	@Test
	public void aPostedConstraintAnswersLikeTheInMemoryReference() throws Exception {
		try (CachingSqlFetch source = source()) {
			Unifiable<Integer> viaSql = lvar();
			Unifiable<Integer> viaDb = lvar();
			// answer SETS agree; enumeration order is the carrier's own
			assertThat(Query.of(person(source, viaSql, lvar()).posted()).solve(viaSql)
					.map(Object::toString)
					.sorted()
					.collect(Collectors.toList()))
					.isEqualTo(Query.of(person(reference, viaDb, lvar()).posted()).solve(viaDb)
							.map(Object::toString)
							.sorted()
							.collect(Collectors.toList()));
		}
	}

	@Test
	public void aRelationWithoutATableFailsLoudlyAtFirstFetch() throws Exception {
		// the backend is the schema authority: no declared relation set —
		// a missing table surfaces as the fetch's own loud failure, naming
		// the SQL it tried
		RelationN orphan = RelationN.of("orphan", id);
		try (CachingSqlFetch source = source()) {
			assertThatThrownBy(() -> Query.of(orphan.apply(source, lvar())).solve(lvar()).count())
					.isInstanceOf(IllegalStateException.class)
					.hasMessageContaining("orphan");
		}
	}

	@Test
	public void aPostedRecordGroundsFromTheLandedPoolWithoutAFetch() throws Exception {
		// the wide fetch lands the whole relation; enforce's row-wise
		// re-wakes probe GROUND patterns the ledger must prove covered —
		// one round trip for the entire posted solve, never per-row
		// existence checks
		try (CachingSqlFetch source = source()) {
			Unifiable<String> out = lvar();
			assertThat(Query.of(person(source, lvar(), out).posted()).solve(out)
					.map(Object::toString)
					.sorted()
					.collect(Collectors.toList()))
					.hasSize(3);
			assertThat(statements.get())
					.describedAs("the sealed pool serves the ground re-wakes")
					.isEqualTo(1);
		}
	}

	@Test
	public void aSubsumedProbeIsServedFromTheLandedPoolWithoutAFetch() throws Exception {
		try (CachingSqlFetch source = source()) {
			solvedNames(source);                       // the wide fetch: nothing bound
			int afterWide = statements.get();

			Unifiable<String> narrow = lvar();
			List<String> viaLanded = Query.of(person(source, lval(2), narrow)).solve(narrow)
					.map(Object::toString)
					.collect(Collectors.toList());

			assertThat(viaLanded).hasSize(1);
			assertThat(viaLanded.get(0)).contains("Alan");
			assertThat(statements.get())
					.describedAs("a probe subsumed by a covered one must not touch the backend")
					.isEqualTo(afterWide);
		}
	}

	@Test
	public void aFirstProbeWithABoundPositionLandsRealValues() throws Exception {
		// the projection fetches only unbound columns; the merge must land the
		// bound VALUE, not its Optional wrapper — and the landed index must
		// answer by it
		try (CachingSqlFetch source = source()) {
			Unifiable<String> out = lvar();
			List<String> answers = Query.of(person(source, lval(2), out)).solve(out)
					.map(Object::toString)
					.collect(Collectors.toList());
			assertThat(answers).hasSize(1);
			assertThat(answers.get(0)).contains("Alan");
		}
	}

	@Test
	public void aFullyBoundFirstProbeIsAnExistenceCheck() throws Exception {
		// every position bound: the projection degenerates — no unbound
		// columns to select — and must still compile to legal SQL
		try (CachingSqlFetch source = source()) {
			assertThat(Query.of(person(source,
					lval(3),
					lval("Kurt"))).solve(lvar())
					.count()).isEqualTo(1);
			assertThat(Query.of(person(source,
					lval(3),
					lval("Ada"))).solve(lvar())
					.count()).isZero();
		}
	}

	private static List<String> solvedNames(AnswerSource source) {
		Unifiable<String> out = lvar();
		return Query.of(person(source, lvar(), out)).solve(out)
				.map(Object::toString)
				.sorted()
				.collect(Collectors.toList());
	}

	/** Counts prepared statements, so the landing receipts can see fetches. */
	private Connection counting(Connection real) {
		return (Connection) Proxy.newProxyInstance(
				getClass().getClassLoader(),
				new Class<?>[]{Connection.class},
				(proxy, method, args) -> {
					if ("prepareStatement".equals(method.getName())) {
						statements.incrementAndGet();
					}
					try {
						return method.invoke(real, args);
					} catch (java.lang.reflect.InvocationTargetException e) {
						throw e.getCause();
					}
				});
	}
}
