package org.clauseway.pldb.sql;

// ABOUTME: Declared nullability: a nullable column carries SQL NULL as Java's own
// ABOUTME: null inside lval — the typed surface intact, IS NULL at the probe.

import org.clauseway.logic.solving.Query;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.clauseway.logic.unification.terms.LVal.lval;
import static org.clauseway.logic.unification.terms.LVar.lvar;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import org.clauseway.logic.nogoods.Exclusion;
import org.clauseway.logic.unification.terms.Unifiable;
import org.clauseway.pldb.AnswerSource;
import org.clauseway.pldb.relations.Literal;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class NullableColumnTest {

	private Connection connection;

	@Before
	public void openDatabase() throws SQLException {
		connection = DriverManager.getConnection("jdbc:h2:mem:");
		try (Statement ddl = connection.createStatement()) {
			ddl.execute("CREATE TABLE person(id INT NOT NULL, name VARCHAR(64))");
			ddl.execute("INSERT INTO person VALUES (1, 'Ada'), (2, NULL)");
		}
	}

	@After
	public void closeDatabase() throws SQLException {
		connection.close();
	}

	/**
	 * name declared nullable: the customer's schema allows it, so do we —
	 * and the column stays a plain {@code Unifiable<String>}.
	 */
	private static Literal person(AnswerSource db, Unifiable<Integer> id, Unifiable<String> name) {
		return Literal.relation(NullableColumnTest.class, "person")
				.arg("id", id).indexed()
				.arg("name", name).nullable()
				.from(db);
	}

	@Test
	public void aNullCellReadsAsABoundNull() throws Exception {
		try (CachingSqlFetch source = CachingSqlFetch.pinned("h2", connection)) {
			Unifiable<String> name = lvar();
			List<String> names = Query.of(person(source, lvar(), name)).solve(name)
					.map(Object::toString)
					.sorted()
					.collect(Collectors.toList());
			assertThat(names).containsExactly("{Ada}", "{null}");
		}
	}

	@Test
	public void aBoundNullMatchesOnlyNullRows() throws Exception {
		try (CachingSqlFetch source = CachingSqlFetch.pinned("h2", connection)) {
			Unifiable<Integer> id = lvar();
			List<String> ids = Query.of(person(source, id, lval((String) null))).solve(id)
					.map(Object::toString)
					.collect(Collectors.toList());
			assertThat(ids).containsExactly("{2}");
		}
	}

	@Test
	public void aNullCellRoundTripsThroughTheFlush() throws Exception {
		SqlFlush.over(connection).flush(Arrays.asList(
				person(null, lval(3), lval((String) null))));
		try (Statement read = connection.createStatement()) {
			assertThat(read.executeQuery("SELECT COUNT(*) FROM person WHERE id = 3 AND name IS NULL")
					.next()).isTrue();
		}
		try (CachingSqlFetch source = CachingSqlFetch.pinned("h2", connection)) {
			Unifiable<Integer> id = lvar();
			assertThat(Query.of(person(source, id, lval((String) null))).solve(id)
					.map(Object::toString)
					.sorted()
					.collect(Collectors.toList())).containsExactly("{2}", "{3}");
		}
	}

	@Test
	public void aPushedDisequalityKeepsTheNullRow() throws Exception {
		// the one place null-as-value and SQL diverge: engine-side
		// null != 'Ada' holds, SQL-side it is UNKNOWN and the row drops —
		// the pushed WHERE must carry OR IS NULL to stay complete
		try (CachingSqlFetch source = CachingSqlFetch.pinned("h2", connection)) {
			Unifiable<Integer> id = lvar();
			Unifiable<String> name = lvar();
			List<String> ids = Query.of(Exclusion.exclude(name.unifies("Ada"))
					.and(person(source, id, name))).solve(id)
					.map(Object::toString)
					.collect(Collectors.toList());
			assertThat(ids).containsExactly("{2}");
		}
	}

	@Test
	public void aWideCoveredFetchServesTheNullBoundProbe() throws Exception {
		// coverage over nulls: the wide probe lands the relation (null cell
		// included), and the null-bound probe is SUBSUMED — Any covers the
		// bound null like any other ground term, and the pool's index keys
		// the null bucket
		try (CachingSqlFetch source = CachingSqlFetch.pinned("h2", connection)) {
			Unifiable<String> name = lvar();
			assertThat(Query.of(person(source, lvar(), name)).solve(name).count()).isEqualTo(2);

			Unifiable<Integer> id = lvar();
			assertThat(Query.of(person(source, id, lval((String) null))).solve(id)
					.map(Object::toString)
					.collect(Collectors.toList())).containsExactly("{2}");
		}
	}

	/** The catcher stays for columns NOT declared nullable. */
	private static Literal strict(AnswerSource db, Unifiable<Integer> id, Unifiable<String> name) {
		return Literal.relation(NullableColumnTest.class, "person")
				.arg("id", id).indexed()
				.arg("name", name)
				.from(db);
	}

	@Test
	public void anUndeclaredColumnStillRefusesNull() throws Exception {
		try (CachingSqlFetch source = CachingSqlFetch.pinned("h2", connection)) {
			Unifiable<String> name = lvar();
			assertThatThrownBy(() -> Query.of(strict(source, lvar(), name)).solve(name)
					.collect(Collectors.toList()))
					.isInstanceOf(IllegalStateException.class)
					.hasMessageContaining("name");
		}
	}

	@Test
	public void aNullOnAStrictColumnRefusesAtTheFlush() {
		assertThatThrownBy(() -> SqlFlush.over(connection).flush(Collections.singletonList(
				strict(null, lval(4), lval(null)))))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("person")
				.hasMessageContaining("name");
	}
}
