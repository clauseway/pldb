package org.clauseway.pldb.sql;

// ABOUTME: The live lane's receipts: the world moves between reads, the cache
// ABOUTME: keeps a solve's view still, and a torn transaction meets the Conflict.

import org.clauseway.logic.solving.Query;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.clauseway.logic.unification.terms.LVal.lval;
import static org.clauseway.logic.unification.terms.LVar.lvar;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import org.clauseway.logic.solving.Call;
import org.clauseway.logic.unification.terms.Any;
import org.clauseway.logic.unification.terms.Term;
import org.clauseway.logic.unification.terms.Unifiable;
import org.clauseway.pldb.AnswerSource;
import org.clauseway.logic.solving.Answer;
import org.clauseway.pldb.relations.Answers;
import org.clauseway.pldb.relations.Literal;
import org.clauseway.pldb.relations.Relation;
import org.clauseway.pldb.transaction.AbstractTransaction;
import org.clauseway.pldb.transaction.Transaction;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class LiveSourceTest {

	private static final String URL = "jdbc:h2:mem:livetest;DB_CLOSE_DELAY=-1";

	private final List<Connection> opened = new ArrayList<>();

	@After
	public void closeConnections() {
		for (Connection connection : opened) {
			try {
				connection.close();
			} catch (SQLException ignored) {
				// closing what a test already closed is fine
			}
		}
	}

	@Before
	public void resetSchema() throws SQLException {
		try (
				Connection admin = DriverManager.getConnection(URL);
				Statement ddl = admin.createStatement()
		) {
			ddl.execute("DROP TABLE IF EXISTS person");
			ddl.execute("DROP TABLE IF EXISTS book");
			ddl.execute("DROP TABLE IF EXISTS watermark");
			ddl.execute("CREATE TABLE person(id INT NOT NULL, name VARCHAR(64) NOT NULL)");
			ddl.execute("CREATE TABLE book(isbn VARCHAR(32) NOT NULL, title VARCHAR(64) NOT NULL)");
			Watermark.schema(ddl);
		}
	}

	private Connection connection() {
		try {
			Connection connection = DriverManager.getConnection(URL);
			opened.add(connection);
			return connection;
		} catch (SQLException e) {
			throw new IllegalStateException(e);
		}
	}

	private static Literal person(AnswerSource db, Unifiable<Integer> id, Unifiable<String> name) {
		return Literal.relation(LiveSourceTest.class, "person")
				.arg("id", id).indexed()
				.arg("name", name)
				.from(db);
	}

	private static Literal book(AnswerSource db, Unifiable<String> isbn, Unifiable<String> title) {
		return Literal.relation(LiveSourceTest.class, "book")
				.arg("isbn", isbn).indexed()
				.arg("title", title)
				.from(db);
	}

	private static Call<Relation> probe(Literal shape) {
		Term<?>[] anys = new Term<?>[shape.getRel().getArgs().length];
		for (int i = 0; i < anys.length; i++) {
			anys[i] = Any.of(i);
		}
		return Call.of(shape.getRel(), Answers.image(anys));
	}

	private static List<String> names(Iterable<Answer<Relation>> answers) {
		return StreamSupport.stream(answers.spliterator(), false)
				.map(answer -> answer.getReified().toString())
				.sorted()
				.collect(Collectors.toList());
	}

	@Test
	public void theLiveWorldMovesBetweenTwoReadsOfOneSource() throws Exception {
		SqlFetch live = SqlFetch.live("live", connection());
		Call<Relation> people = probe(person(null, lvar(), lvar()));
		assertThat(live.answers(people)).isEmpty();

		try (Statement writer = connection().createStatement()) {
			writer.execute("INSERT INTO person VALUES (1, 'Ada')");
		}

		// the same source, the same probe, a DIFFERENT world: no snapshot
		// stands between the reads — this is the medium the pinned lane
		// deliberately refuses to be
		assertThat(names(live.answers(people))).containsExactly("{({1}, {Ada})}");
	}

	@Test
	public void theCachingLedgerKeepsASolveViewStill() throws Exception {
		try (CachingSqlFetch source = CachingSqlFetch.live("cached-live", connection())) {
			Unifiable<String> name = lvar();
			assertThat(Query.of(person(source, lvar(), name)).solve(name)
					.map(Object::toString)
					.collect(Collectors.toList())).isEmpty();

			try (Statement writer = connection().createStatement()) {
				writer.execute("INSERT INTO person VALUES (1, 'Ada')");
			}

			// the world moved, the covered probe did not: repeats serve from
			// the ledger — read stability is the CACHE's job on this lane
			assertThat(Query.of(person(source, lvar(), name)).solve(name)
					.map(Object::toString)
					.collect(Collectors.toList())).isEmpty();
		}
	}

	@Test
	public void aTornTransactionMeetsTheConflict() throws Exception {
		Transaction torn = transaction("torn");
		assertThat(names(torn.answers(probe(person(null, lvar(), lvar()))))).isEmpty();

		try (
				Transaction mover = transaction("mover")
						.asserting(Collections.singletonList(person(null, lval(1), lval("Ada"))))
		) {
			mover.commit();
		}

		// book reads the CURRENT world while person was pinned before the
		// move: the transaction's own view is torn across two worlds, and
		// the commit proof must refuse it
		assertThat(names(torn.answers(probe(book(null, lvar(), lvar()))))).isEmpty();
		Transaction staged = torn.asserting(Collections.singletonList(
				book(null, lval("978-0"), lval("SICP"))));
		assertThatThrownBy(() -> staged.commit())
				.describedAs("person moved after its pin — a torn view must not land")
				.isInstanceOf(Transaction.Conflict.class);
	}

	@Test
	public void disjointTransactionsCommitAcrossEachOther() throws Exception {
		// the torn receipt's complement: a mid-flight foreign commit to a
		// DIFFERENT relation must not bounce this one, even though its pins
		// were captured on both sides of that commit
		Transaction people = transaction("people");
		assertThat(names(people.answers(probe(person(null, lvar(), lvar()))))).isEmpty();

		try (
				Transaction books = transaction("books")
						.asserting(Collections.singletonList(book(null, lval("978-0"), lval("SICP"))))
		) {
			books.commit();
		}

		assertThat(names(people.answers(probe(person(null, lvar(), lvar()))))).isEmpty();
		Transaction staged = people.asserting(Collections.singletonList(
				person(null, lval(1), lval("Ada"))));
		staged.commit(); // only book moved — person pins spanning the foreign commit still hold
	}

	@Test
	public void aSharedSourceServesEachTransactionTheCurrentWorld() throws Exception {
		// TWO transactions over ONE live source: the first's reads must not
		// poison the second's — a fresh pin beside source-cached stale rows
		// would be the capture contract violated (data the pin never named)
		Watermark shared = Watermark.over(
				SqlFetch.live("shared", connection()), this::connection);
		Transaction first = AbstractTransaction.over(shared);
		assertThat(names(first.answers(probe(person(null, lvar(), lvar()))))).isEmpty();

		try (
				Transaction mover = transaction("mover")
						.asserting(Collections.singletonList(person(null, lval(1), lval("Ada"))))
		) {
			mover.commit();
		}

		Transaction second = AbstractTransaction.over(shared);
		assertThat(names(second.answers(probe(person(null, lvar(), lvar())))))
				.describedAs("the second transaction's first touch must read the CURRENT world,"
						+ " not the first transaction's cached one")
				.containsExactly("{({1}, {Ada})}");
	}

	private Transaction transaction(String id) {
		return AbstractTransaction.over(
				Watermark.over(SqlFetch.live(id, connection()), this::connection));
	}
}
