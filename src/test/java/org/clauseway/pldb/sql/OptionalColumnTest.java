package org.clauseway.pldb.sql;

// ABOUTME: A nullable SQL column is an LOption column with an optional codec: NULL
// ABOUTME: reads as none(), a value as some(v), the probe's shape compiles to the WHERE.

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.clauseway.logic.unification.structures.LOption.none;
import static org.clauseway.logic.unification.structures.LOption.some;
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
import org.clauseway.logic.solving.Query;
import org.clauseway.logic.unification.structures.LOption;
import org.clauseway.logic.unification.terms.Unifiable;
import org.clauseway.pldb.AnswerSource;
import org.clauseway.pldb.relations.literals.External;
import org.clauseway.pldb.relations.literals.Literal;
import org.clauseway.pldb.relations.Schema;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class OptionalColumnTest {

	private static final Codec<LOption<String>> OPTIONAL_TEXT = Codec.optional(String.class);

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

	/** The column's type says it may be absent; the codec says how NULL crosses the wire. */
	private static External<?> person(AnswerSource db, Unifiable<Integer> id, Unifiable<LOption<String>> name) {
		return Literal.relation(OptionalColumnTest.class, "person")
				.args(Schema.builder()
						.arg("id", id).indexed()
						.arg("name", name)
						.build())
				.from(db);
	}

	private CachingSqlFetch source() {
		return CachingSqlFetch.pinned("h2", connection)
				.withCodec(person(null, lvar(), OPTIONAL_TEXT.arg()));
	}

	@Test
	public void aNullCellReadsAsNone() throws Exception {
		try (CachingSqlFetch source = source()) {
			Unifiable<LOption<String>> name = lvar();
			List<String> names = Query.of(person(source, lvar(), name)).solve(name)
					.map(Object::toString)
					.sorted()
					.collect(Collectors.toList());
			assertThat(names).containsExactly("{None}", "{Some({Ada})}");
		}
	}

	@Test
	public void aNoneProbeIsWhereIsNull() throws Exception {
		try (CachingSqlFetch source = source()) {
			Unifiable<Integer> id = lvar();
			assertThat(Query.of(person(source, id, none())).solve(id)
					.map(Object::toString)
					.collect(Collectors.toList())).containsExactly("{2}");
		}
	}

	@Test
	public void aSomeProbeIsWhereIsNotNullAndBindsTheValue() throws Exception {
		try (CachingSqlFetch source = source()) {
			Unifiable<Integer> id = lvar();
			Unifiable<String> name = lvar();
			assertThat(Query.of(person(source, id, some(name))).select(id, name).stream()
					.map(row -> row.get(id).get() + ":" + row.get(name).get())
					.collect(Collectors.toList())).containsExactly("1:Ada");
		}
	}

	@Test
	public void aGroundSomeProbeIsAnEquality() throws Exception {
		try (CachingSqlFetch source = source()) {
			Unifiable<Integer> id = lvar();
			assertThat(Query.of(person(source, id, some(lval("Ada")))).solve(id)
					.map(Object::toString)
					.collect(Collectors.toList())).containsExactly("{1}");
		}
	}

	@Test
	public void bothShapesRoundTripThroughTheFlush() throws Exception {
		Codecs codecs = Codecs.builtin().withCodec(person(null, lvar(), OPTIONAL_TEXT.arg()));
		SqlFlush.over(connection, codecs).flush(Arrays.asList(
				person(null, lval(3), none()),
				person(null, lval(4), some(lval("Kurt")))));
		try (Statement read = connection.createStatement()) {
			assertThat(read.executeQuery("SELECT 1 FROM person WHERE id = 3 AND name IS NULL").next()).isTrue();
			assertThat(read.executeQuery("SELECT 1 FROM person WHERE id = 4 AND name = 'Kurt'").next()).isTrue();
		}
		try (CachingSqlFetch source = source()) {
			Unifiable<Integer> id = lvar();
			assertThat(Query.of(person(source, id, none())).solve(id)
					.map(Object::toString)
					.sorted()
					.collect(Collectors.toList())).containsExactly("{2}", "{3}");
		}
	}

	@Test
	public void aPushedDisequalityKeepsTheNoneRow() throws Exception {
		// engine-side None ≠ Some(Ada) holds; SQL's NULL <> 'Ada' is UNKNOWN
		// and would drop the row — the pushed WHERE carries OR IS NULL
		try (CachingSqlFetch source = source()) {
			Unifiable<Integer> id = lvar();
			Unifiable<LOption<String>> name = lvar();
			List<String> ids = Query.of(Exclusion.exclude(name.unifies(some(lval("Ada"))))
					.and(person(source, id, name))).solve(id)
					.map(Object::toString)
					.collect(Collectors.toList());
			assertThat(ids).containsExactly("{2}");
		}
	}

	@Test
	public void aWideCoveredFetchServesTheNoneProbe() throws Exception {
		try (CachingSqlFetch source = source()) {
			Unifiable<LOption<String>> name = lvar();
			assertThat(Query.of(person(source, lvar(), name)).solve(name).count()).isEqualTo(2);

			Unifiable<Integer> id = lvar();
			assertThat(Query.of(person(source, id, none())).solve(id)
					.map(Object::toString)
					.collect(Collectors.toList())).containsExactly("{2}");
		}
	}

	/** A column with no optional codec: a NULL cell still refuses by relation and column. */
	private static External<?> strict(AnswerSource db, Unifiable<Integer> id, Unifiable<String> name) {
		return Literal.relation(OptionalColumnTest.class, "person")
				.args(Schema.builder()
						.arg("id", id).indexed()
						.arg("name", name)
						.build())
				.from(db);
	}

	@Test
	public void anUndeclaredColumnStillRefusesNull() throws Exception {
		try (CachingSqlFetch source = CachingSqlFetch.pinned("h2", connection)) {
			Unifiable<String> name = lvar();
			assertThatThrownBy(() -> Query.of(strict(source, lvar(), name)).solve(name)
					.collect(Collectors.toList()))
					.isInstanceOf(IllegalStateException.class)
					.hasMessageContaining("person")
					.hasMessageContaining("name")
					.hasMessageContaining("optional");
		}
	}

	@Test
	public void aNoneOnAnUndeclaredColumnRefusesAtTheFlush() {
		assertThatThrownBy(() -> SqlFlush.over(connection).flush(Collections.singletonList(
				person(null, lval(4), none()))))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("person")
				.hasMessageContaining("name");
	}
}
