package org.clauseway.pldb.transaction;

// ABOUTME: The Transaction over the owned certify tier: watermark receipts on H2 —
// ABOUTME: refusal without a capability, write skew refused, disjoint relations pass.

import org.clauseway.logic.solving.Query;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.clauseway.logic.unification.terms.LVal.lval;
import static org.clauseway.logic.unification.terms.LVar.lvar;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import org.clauseway.logic.unification.terms.Unifiable;
import org.clauseway.pldb.AnswerSource;
import org.clauseway.pldb.relations.Literal;
import org.clauseway.pldb.sql.SqlFetch;
import org.clauseway.pldb.sql.Watermark;
import org.junit.Before;
import org.junit.Test;

public class TransactionTest {

	private static final String URL = "jdbc:h2:mem:txtest;DB_CLOSE_DELAY=-1";

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

	private static Transaction transaction(String id) throws Exception {
		Connection connection = DriverManager.getConnection(URL);
		return AbstractTransaction.over(Watermark.over(SqlFetch.pinned(id, connection),
				TransactionTest::commitConnection));
	}

	private static Connection commitConnection() {
		try {
			return DriverManager.getConnection(URL);
		} catch (SQLException e) {
			throw new IllegalStateException(e);
		}
	}

	private static Literal person(AnswerSource db, Unifiable<Integer> id, Unifiable<String> name) {
		return Literal.relation(TransactionTest.class, "person")
				.arg("id", id).indexed()
				.arg("name", name)
				.from(db);
	}

	private static Literal book(AnswerSource db, Unifiable<String> isbn, Unifiable<String> title) {
		return Literal.relation(TransactionTest.class, "book")
				.arg("isbn", isbn).indexed()
				.arg("title", title)
				.from(db);
	}

	private static List<String> names(AnswerSource db) {
		Unifiable<String> name = lvar();
		return Query.of(person(db, lvar(), name)).solve(name)
				.map(Object::toString)
				.sorted()
				.collect(Collectors.toList());
	}

	private static List<String> titles(AnswerSource db) {
		Unifiable<String> title = lvar();
		return Query.of(book(db, lvar(), title)).solve(title)
				.map(Object::toString)
				.sorted()
				.collect(Collectors.toList());
	}

	// a source without a certify capability has no Transaction.over overload to
	// call — the refusal moved from runtime into the type system, so its old
	// receipt (aSourceWithoutACertifyCapabilityRefusesAtOpen) is now javac's job

	@Test
	public void aProtocolRetractionAdvancesTheMarkAndBouncesThePinnedReader() throws Exception {
		// the counter-pin sees polarity by construction: advance runs over
		// the union, so a retraction divides pins like any insert — no
		// data-derived witness needed at relation grain
		try (
				Transaction seed = transaction("seed")
						.asserting(Collections.singletonList(person(null, lval(1), lval("Ada"))))
		) {
			seed.commit();
		}

		Transaction reader = transaction("reader");
		assertThat(names(reader)).containsExactly("{Ada}");

		try (
				Transaction mover = transaction("mover")
						.retracting(Collections.singletonList(person(null, lval(1), lval("Ada"))))
		) {
			mover.commit();
		}

		try (Transaction after = transaction("after")) {
			assertThat(names(after)).isEmpty();
		}

		assertThatThrownBy(() -> reader.asserting(Collections.singletonList(
				book(null, lval("i1"), lval("Tar Pit")))).commit())
				.isInstanceOf(Transaction.Conflict.class);
	}

	@Test
	public void commitLandsStagedFactsForTheNextTransaction() throws Exception {
		Transaction writer = transaction("w")
				.asserting(Collections.singletonList(person(null, lval(1), lval("Ada"))));
		assertThat(names(writer)).containsExactly("{Ada}");
		writer.commit();

		try (Transaction reader = transaction("r")) {
			assertThat(names(reader)).containsExactly("{Ada}");
		}
	}

	@Test
	public void writeSkewOnOneRelationMeetsTheConflict() throws Exception {
		// both transactions read the person region their sibling writes: the
		// first commit wins, the second's watermark moved — Conflict, re-solve
		Transaction first = transaction("first");
		Transaction second = transaction("second");
		assertThat(names(first)).isEmpty();
		assertThat(names(second)).isEmpty();

		first = first.asserting(Collections.singletonList(
				person(null, lval(1), lval("Ada"))));
		second = second.asserting(Collections.singletonList(
				person(null, lval(2), lval("Alan"))));

		first.commit();
		Transaction loser = second;
		assertThatThrownBy(loser::commit).isInstanceOf(Transaction.Conflict.class);

		try (Transaction reader = transaction("r")) {
			assertThat(names(reader)).containsExactly("{Ada}");
		}
	}

	@Test
	public void disjointRelationsCommitWithoutConflict() throws Exception {
		// the per-relation marks earn their keep: one transaction read only
		// person, the other committed only book — no conflict between them
		try (
				Transaction seed = transaction("seed")
						.asserting(Collections.singletonList(person(null, lval(1), lval("Ada"))))
						.asserting(Collections.singletonList(book(null, lval("978-0"), lval("SICP"))))
		) {
			seed.commit();
		}

		Transaction bookWriter = transaction("books");
		Transaction personWriter = transaction("people");
		assertThat(titles(bookWriter)).containsExactly("{SICP}");
		assertThat(names(personWriter)).containsExactly("{Ada}");

		bookWriter = bookWriter.asserting(Collections.singletonList(
				book(null, lval("978-1"), lval("TAPL"))));
		personWriter = personWriter.asserting(Collections.singletonList(
				person(null, lval(2), lval("Alan"))));

		bookWriter.commit();
		personWriter.commit(); // person marks never moved — the book commit must not bounce this one
	}

	@Test
	public void aNeverWrittenRelationSurvivesAnUnrelatedCommit() throws Exception {
		// person has never been written, so it has no watermark row on either
		// side of the read — double absence proves silence (any write would
		// have minted the row), and an unrelated book commit must not bounce it
		Transaction reader = transaction("reader");
		assertThat(names(reader)).isEmpty();

		try (
				Transaction bookWriter = transaction("books")
						.asserting(Collections.singletonList(book(null, lval("978-0"), lval("SICP"))))
		) {
			bookWriter.commit();
		}

		reader = reader.asserting(Collections.singletonList(
				person(null, lval(1), lval("Ada"))));
		reader.commit(); // person never moved — absence at pin and at commit is proof, not blindness
	}

	@Test
	public void aWriteOnlyTransactionCommitsPastAnyConcurrentCommit() throws Exception {
		// no reads recorded: an empty footprint certifies vacuously — a decision
		// that stood on no reads cannot have stood on stale ones
		Transaction blind = transaction("blind")
				.asserting(Collections.singletonList(person(null, lval(1), lval("Ada"))));

		try (
				Transaction other = transaction("other")
						.asserting(Collections.singletonList(book(null, lval("978-0"), lval("SICP"))))
		) {
			other.commit();
		}

		blind.commit();
		try (Transaction check = transaction("check")) {
			assertThat(names(check)).containsExactly("{Ada}");
		}
	}

	@Test
	public void anAbandonedTransactionLeavesNoTrace() throws Exception {
		try (
				Transaction abandoned = transaction("a")
						.asserting(Collections.singletonList(person(null, lval(1), lval("Ada"))))
		) {
			assertThat(names(abandoned)).containsExactly("{Ada}");
		}
		try (Transaction reader = transaction("r")) {
			assertThat(names(reader)).isEmpty();
		}
	}
}
