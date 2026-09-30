package org.clauseway.pldb.sql;

// ABOUTME: The codec map: builtins pass through, column codecs register through a
// ABOUTME: template literal on the SOURCE — serialization is the backend's concern.

import org.clauseway.logic.solving.Query;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.clauseway.logic.unification.terms.LVal.lval;
import static org.clauseway.logic.unification.terms.LVar.lvar;

import java.sql.Connection;
import java.sql.Date;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import org.clauseway.logic.unification.terms.Term;
import org.clauseway.logic.unification.terms.Unifiable;
import org.clauseway.pldb.AnswerSource;
import org.clauseway.pldb.relations.Literal;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class CodecTest {

	private static final Codec<LocalDate> AS_DATE =
			Codec.of(LocalDate.class, Date.class, Date::valueOf, Date::toLocalDate);
	private static final DateTimeFormatter BASIC = DateTimeFormatter.BASIC_ISO_DATE;
	private static final Codec<LocalDate> AS_TEXT =
			Codec.of(LocalDate.class, String.class,
					d -> d.format(BASIC), s -> LocalDate.parse(s, BASIC));

	private Connection connection;

	@Before
	public void openDatabase() throws SQLException {
		connection = DriverManager.getConnection("jdbc:h2:mem:");
		try (Statement ddl = connection.createStatement()) {
			ddl.execute("CREATE TABLE loan(id INT NOT NULL, due DATE NOT NULL)");
			ddl.execute("CREATE TABLE event(at DATE NOT NULL, logged VARCHAR(8) NOT NULL)");
		}
	}

	@After
	public void closeDatabase() throws SQLException {
		connection.close();
	}

	private static Literal loan(AnswerSource db, Unifiable<Integer> id, Unifiable<LocalDate> due) {
		return Literal.relation(CodecTest.class, "loan")
				.arg("id", id).indexed()
				.arg("due", due)
				.from(db);
	}

	/**
	 * One Java type, TWO wire encodings in one relation — the codec is a
	 * property of the COLUMN, addressed through the defining method.
	 */
	private static Literal event(AnswerSource db, Unifiable<LocalDate> at, Unifiable<LocalDate> logged) {
		return Literal.relation(CodecTest.class, "event")
				.arg("at", at).indexed()
				.arg("logged", logged)
				.from(db);
	}

	@Test
	public void aColumnCodecRoundTripsThroughFlushAndFetch() throws Exception {
		Codecs codecs = Codecs.builtin().withCodec(loan(null, lvar(), AS_DATE.arg()));
		SqlFlush.over(connection, codecs).flush(Arrays.asList(
				loan(null, lval(1), lval(LocalDate.of(2026, 9, 11))),
				loan(null, lval(2), lval(LocalDate.of(2026, 12, 24)))));

		try (
				CachingSqlFetch source = CachingSqlFetch.pinned("h2", connection)
						.withCodec(loan(null, lvar(), AS_DATE.arg()))
		) {
			Unifiable<LocalDate> due = lvar();
			List<LocalDate> dues = Query.of(loan(source, lvar(), due)).solve(due)
					.map(Term::get)
					.sorted()
					.collect(Collectors.toList());
			assertThat(dues).containsExactly(
					LocalDate.of(2026, 9, 11), LocalDate.of(2026, 12, 24));
		}
	}

	@Test
	public void oneTypeTwoEncodingsInOneRelation() throws Exception {
		Codecs codecs = Codecs.builtin().withCodec(event(null, AS_DATE.arg(), AS_TEXT.arg()));
		SqlFlush.over(connection, codecs).flush(Arrays.asList(
				event(null, lval(LocalDate.of(2026, 9, 11)), lval(LocalDate.of(2026, 9, 12)))));
		try (Statement read = connection.createStatement()) {
			assertThat(read.executeQuery(
							"SELECT 1 FROM event WHERE at = DATE '2026-09-11' AND logged = '20260912'")
					.next()).isTrue();
		}
		try (
				CachingSqlFetch source = CachingSqlFetch.pinned("h2", connection)
						.withCodec(event(null, AS_DATE.arg(), AS_TEXT.arg()))
		) {
			Unifiable<LocalDate> logged = lvar();
			assertThat(Query.of(event(source, lvar(), logged)).solve(logged)
					.map(Term::get)
					.collect(Collectors.toList())).containsExactly(LocalDate.of(2026, 9, 12));
		}
	}

	@Test
	public void aBoundProbeEncodesItsParameter() throws Exception {
		Codecs codecs = Codecs.builtin().withCodec(loan(null, lvar(), AS_DATE.arg()));
		SqlFlush.over(connection, codecs).flush(Arrays.asList(
				loan(null, lval(1), lval(LocalDate.of(2026, 9, 11))),
				loan(null, lval(2), lval(LocalDate.of(2026, 12, 24)))));

		try (
				CachingSqlFetch source = CachingSqlFetch.pinned("h2", connection)
						.withCodec(loan(null, lvar(), AS_DATE.arg()))
		) {
			Unifiable<Integer> id = lvar();
			assertThat(Query.of(loan(source, id, lval(LocalDate.of(2026, 12, 24)))).solve(id)
					.map(Object::toString)
					.collect(Collectors.toList())).containsExactly("{2}");
		}
	}

	@Test
	public void anUnregisteredTypeRefusesAtTheFlushByColumn() {
		assertThatThrownBy(() -> SqlFlush.over(connection).flush(Arrays.asList(
				loan(null, lval(1), lval(LocalDate.of(2026, 9, 11))))))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("loan")
				.hasMessageContaining("due")
				.hasMessageContaining("LocalDate");
	}

	@Test
	public void aStructuralValueKeepsTheModellingRefusal() {
		Literal bad = Literal.relation(CodecTest.class, "loan")
				.arg("id", lval(1)).indexed()
				.arg("due", lval((Object) Arrays.asList(1, 2)))
				.from(null);
		assertThatThrownBy(() -> SqlFlush.over(connection).flush(Arrays.asList(bad)))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("child relation");
	}

	@Test
	public void registrationAfterFirstUseRefuses() throws Exception {
		try (Statement ddl = connection.createStatement()) {
			ddl.execute("CREATE TABLE person(id INT NOT NULL, name VARCHAR(16) NOT NULL)");
			ddl.execute("INSERT INTO person VALUES (1, 'Ada')");
		}
		try (CachingSqlFetch source = CachingSqlFetch.pinned("h2", connection)) {
			Unifiable<String> name = lvar();
			Query.of(Literal.relation(CodecTest.class, "person").arg("id", lvar()).indexed().arg("name", name).from(source)).solve(name).collect(Collectors.toList());
			assertThatThrownBy(() -> source.withCodec(loan(null, lvar(), AS_DATE.arg())))
					.isInstanceOf(IllegalStateException.class)
					.hasMessageContaining("before first use");
		}
	}
}
