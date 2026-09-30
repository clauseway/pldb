package org.clauseway.pldb.sql;

// ABOUTME: Pins the pushdown core: the coverage trap (a pushed fetch must not serve
// ABOUTME: a wider probe), answer identity vs the unpushed source, locality receipts.

import org.clauseway.logic.solving.Query;
import static org.assertj.core.api.Assertions.assertThat;
import static org.clauseway.logic.finitedomain.FiniteDomain.dom;
import static org.clauseway.logic.nogoods.Exclusion.exclude;
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
import org.clauseway.logic.finitedomain.FiniteDomainConstraints;
import org.clauseway.logic.finitedomain.Longs;
import org.clauseway.logic.unification.terms.Unifiable;
import org.clauseway.pldb.AnswerSource;
import org.clauseway.pldb.inmemory.AnswerStore;
import org.clauseway.pldb.relations.Literal;
import org.clauseway.pldb.relations.Property;
import org.clauseway.pldb.relations.Relation;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class SqlPushdownTest {

	private static Literal person(AnswerSource db, Unifiable<Long> id, Unifiable<String> name) {
		return Literal.relation(SqlPushdownTest.class, "person")
				.arg("id", id).indexed()
				.arg("name", name)
				.from(db);
	}

	private static Relation personRel() {
		return person(null, lvar(), lvar()).getRel();
	}

	private static final Property<Long> id = Property.of("id");
	private static final Property<String> name = Property.of("name");

	private static final AnswerStore reference = AnswerStore.empty()
			.asserting(Arrays.asList(
					person(null, lval(1L), lval("Ada")),
					person(null, lval(2L), lval("Alan")),
					person(null, lval(3L), lval("Kurt"))));

	private Connection connection;
	private final AtomicInteger statements = new AtomicInteger();
	private final List<String> statementSql = new java.util.ArrayList<>();

	@Before
	public void loadH2() throws SQLException {
		connection = DriverManager.getConnection("jdbc:h2:mem:");
		try (Statement ddl = connection.createStatement()) {
			ddl.execute("CREATE TABLE person(id BIGINT, name VARCHAR(64))");
			ddl.execute("INSERT INTO person VALUES (1, 'Ada'), (2, 'Alan'), (3, 'Kurt')");
		}
	}

	@After
	public void closeH2() throws SQLException {
		connection.close();
	}

	/** Sources share the test's connection; the @After close owns its lifecycle. */
	private CachingSqlFetch pushing() {
		return CachingSqlFetch.pinned("h2-push", counting(connection));
	}

	/** The unpushed leg of the oracle: the bare halves composed, no equipment. */
	private AnswerSource plain() {
		return CachingAnswerSource.over(SqlFetch.pinned("h2-plain", counting(connection)));
	}

	@Test
	public void aPushedFetchMustNotServeAWiderProbe() {
		// THE TRAP: the narrow fetch lands rows 1..2 under its pushed WHERE;
		// coverage recorded by pattern alone would claim completeness for the
		// whole relation and silently lose Kurt
		CachingSqlFetch source = pushing();
		Unifiable<Long> narrow = lvar();
		assertThat(Query.of(dom(narrow, Longs.range(1, 3))
				.and(person(source, narrow, lvar()))).solve(narrow)
				.count()).isEqualTo(2);

		Unifiable<String> everyone = lvar();
		assertThat(Query.of(person(source, lvar(), everyone)).solve(everyone)
				.count()).isEqualTo(3);
	}

	@Test
	public void answersMatchTheUnpushedSourceOnDomains() {
		List<String> pushed = domProgram(pushing());
		List<String> unpushed = domProgram(plain());
		assertThat(pushed).isEqualTo(unpushed);
		assertThat(pushed).hasSize(2);
	}

	@Test
	public void answersMatchTheUnpushedSourceOnComparisons() {
		List<String> pushed = leqProgram(pushing());
		List<String> unpushed = leqProgram(plain());
		assertThat(pushed).isEqualTo(unpushed);
		assertThat(pushed).hasSize(2);
	}

	@Test
	public void aNarrowerProbeAfterAPushedFetchStaysLocal() {
		CachingSqlFetch source = pushing();
		Unifiable<Long> wide = lvar();
		assertThat(Query.of(dom(wide, Longs.range(1, 4))
				.and(person(source, wide, lvar()))).solve(wide)
				.count()).isEqualTo(3);
		int afterPushed = statements.get();

		Unifiable<Long> narrower = lvar();
		assertThat(Query.of(dom(narrower, Longs.range(1, 3))
				.and(person(source, narrower, lvar()))).solve(narrower)
				.count()).isEqualTo(2);
		assertThat(statements.get())
				.describedAs("a probe whose region is contained in a covered one must stay local")
				.isEqualTo(afterPushed);
	}

	@Test
	public void theFdCompilerIsEquipment() {
		// no compiling(...) call anywhere: the FD compiler is wired at
		// pinned() — the domain still reaches the WHERE clause
		CachingSqlFetch source = CachingSqlFetch.pinned("h2-equipment", counting(connection));
		domProgram(source);
		assertThat(statementSql.stream().anyMatch(sql -> sql.contains("id IN (?, ?)")))
				.describedAs("the auto-registered FD compiler must push the domain")
				.isTrue();
	}

	@Test
	public void anAutoRegisteredCompilerIsOverridable() {
		// a user replacement takes the family over: an always-refusing
		// compiler keeps every FD atom local
		CachingSqlFetch source = CachingSqlFetch.pinned("h2-override", counting(connection))
				.compiling(FiniteDomainConstraints.class, (atom, columns) -> java.util.Optional.empty());
		domProgram(source);
		assertThat(statementSql.stream().noneMatch(sql -> sql.contains("IN (")))
				.describedAs("the override must replace the built-in")
				.isTrue();
	}

	@Test
	public void aPartiallyCompiledFusedExclusionStaysSound() {
		// the oracle answer to the conjunct-drop doubt: two same-surface
		// exclusions fuse; with FD's compiler overridden to refuse, the
		// dom-literal conjunct drops and only the binding conjunct pushes —
		// a WEAKER WHERE, the dropped conjunct enforced locally, answers
		// identical to the reference enforcing both
		CachingSqlFetch partial = CachingSqlFetch.pinned("h2-partial", counting(connection))
				.compiling(FiniteDomainConstraints.class, (atom, columns) -> java.util.Optional.empty());
		Unifiable<Long> viaSql = lvar();
		Unifiable<Long> viaMemory = lvar();
		List<String> pushed = fusedProgram(partial, viaSql);
		assertThat(pushed).isEqualTo(fusedProgram(reference, viaMemory));
		assertThat(pushed).hasSize(1);
		assertThat(statementSql.stream().anyMatch(text -> text.contains("id <> ?")))
				.describedAs("the binding conjunct must still push")
				.isTrue();
		assertThat(statementSql.stream().noneMatch(text -> text.contains("IN (")))
				.describedAs("the refused dom conjunct must stay local")
				.isTrue();
	}

	private static List<String> fusedProgram(AnswerSource source, Unifiable<Long> x) {
		return Query.of(exclude(x.unifies(2L))
				.and(exclude(dom(x, Longs.range(1, 3))))
				.and(person(source, x, lvar()))).solve(x)
				.map(Object::toString)
				.sorted()
				.collect(Collectors.toList());
	}

	@Test
	public void doubleNegationIsBooleanOnBothSides() {
		// exclude(exclude(x=3)) IS x=3, engine and SQL alike. Engine-side the
		// trial keeps the equivalence: away from 3 the inner exclusion is
		// SATISFIED and discharges — the scratch comes back unchanged, the
		// literal crosses off, the outer nogood fires; at 3 the inner is
		// doomed, the literal refutes, the outer is satisfied. The compiler's
		// registry self-delegation pushes NOT (id <> ?), and the oracle
		// agrees. (The exactness bit still guards the flip: a WEAKENED inner
		// compilation would refuse its negation.)
		Unifiable<Long> viaSql = lvar();
		Unifiable<Long> viaMemory = lvar();
		List<String> pushed = doubleNegationProgram(pushing(), viaSql);
		assertThat(pushed).isEqualTo(doubleNegationProgram(reference, viaMemory));
		assertThat(pushed).hasSize(1);
		assertThat(statementSql.stream().anyMatch(text -> text.contains("NOT (id <> ?)")))
				.describedAs("the double negation must reach the backend as the pushed complement")
				.isTrue();
	}

	private static List<String> doubleNegationProgram(AnswerSource source, Unifiable<Long> x) {
		return Query.of(exclude(exclude(x.unifies(3L)))
				.and(person(source, x, lvar()))).solve(x)
				.map(Object::toString)
				.sorted()
				.collect(Collectors.toList());
	}

	@Test
	public void anExclusionPushesItsNegation() {
		// the nogood compiler is equipment too: the one-literal exclusion
		// reaches the WHERE clause as its negation, answers unchanged
		Unifiable<Long> viaSql = lvar();
		Unifiable<Long> viaMemory = lvar();
		List<String> sql = exclusionProgram(pushing(), viaSql);
		assertThat(sql).isEqualTo(exclusionProgram(reference, viaMemory));
		assertThat(sql).hasSize(2);
		assertThat(statementSql.stream().anyMatch(text -> text.contains("id <> ?")))
				.describedAs("the exclusion must reach the backend")
				.isTrue();
	}

	private static List<String> domProgram(AnswerSource source) {
		Unifiable<Long> x = lvar();
		Unifiable<String> out = lvar();
		return Query.of(dom(x, Longs.range(1, 3))
				.and(person(source, x, out))).solve(out)
				.map(Object::toString)
				.sorted()
				.collect(Collectors.toList());
	}

	private static List<String> leqProgram(AnswerSource source) {
		Unifiable<Long> x = lvar();
		Unifiable<String> out = lvar();
		return Query.of(Longs.leq(x, lval(2L))
				.and(person(source, x, out))).solve(out)
				.map(Object::toString)
				.sorted()
				.collect(Collectors.toList());
	}

	private static List<String> exclusionProgram(AnswerSource source, Unifiable<Long> x) {
		return Query.of(exclude(x.unifies(2L))
				.and(person(source, x, lvar()))).solve(x)
				.map(Object::toString)
				.sorted()
				.collect(Collectors.toList());
	}

	/** Counts prepared statements, so the locality receipts can see fetches. */
	private Connection counting(Connection real) {
		return (Connection) Proxy.newProxyInstance(
				getClass().getClassLoader(),
				new Class<?>[]{Connection.class},
				(proxy, method, args) -> {
					if ("prepareStatement".equals(method.getName())) {
						statements.incrementAndGet();
						statementSql.add(String.valueOf(args[0]));
					}
					try {
						return method.invoke(real, args);
					} catch (java.lang.reflect.InvocationTargetException e) {
						throw e.getCause();
					}
				});
	}
}
