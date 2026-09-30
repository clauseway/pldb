package org.clauseway.pldb.sql;

// ABOUTME: Region-grain receipts: disjoint regions of ONE relation commit across
// ABOUTME: each other, an insert into a pinned region bounces, no column refuses.

import org.clauseway.functional.fibers.schedulers.ForkJoinScheduler;
import org.clauseway.logic.solving.Query;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.clauseway.logic.finitedomain.FiniteDomain.dom;
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
import org.clauseway.logic.finitedomain.Longs;
import org.clauseway.logic.unification.terms.Term;
import org.clauseway.logic.unification.terms.Unifiable;
import org.clauseway.pldb.AnswerSource;
import org.clauseway.pldb.relations.Literal;
import org.clauseway.pldb.transaction.AbstractTransaction;
import org.clauseway.pldb.transaction.Transaction;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class VersionedWatermarkTest {

	private static final String URL = "jdbc:h2:mem:versioned;DB_CLOSE_DELAY=-1";

	private final List<Connection> opened = new ArrayList<>();

	@Before
	public void resetSchema() throws SQLException {
		try (
				Connection admin = DriverManager.getConnection(URL);
				Statement ddl = admin.createStatement()
		) {
			ddl.execute("DROP TABLE IF EXISTS loan");
			ddl.execute("DROP TABLE IF EXISTS book");
			ddl.execute("DROP TABLE IF EXISTS watermark");
			ddl.execute("CREATE TABLE loan(member VARCHAR(16) NOT NULL, copy VARCHAR(16) NOT NULL,"
					+ " version BIGINT NOT NULL)");
			ddl.execute("CREATE TABLE book(isbn VARCHAR(32) NOT NULL, title VARCHAR(64) NOT NULL)");
			ddl.execute("DROP TABLE IF EXISTS invoice");
			ddl.execute("CREATE TABLE invoice(member VARCHAR(16) NOT NULL, due BIGINT NOT NULL,"
					+ " version BIGINT NOT NULL)");
			Watermark.schema(ddl);
		}
	}

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

	private Connection connection() {
		try {
			Connection connection = DriverManager.getConnection(URL);
			opened.add(connection);
			return connection;
		} catch (SQLException e) {
			throw new IllegalStateException(e);
		}
	}

	private Transaction transaction(String id) {
		return AbstractTransaction.over(
				VersionedWatermark.over(SqlFetch.live(id, connection()), this::connection));
	}

	private static Literal loan(AnswerSource db, Unifiable<String> member, Unifiable<String> copy) {
		return Literal.relation(VersionedWatermarkTest.class, "loan")
				.arg("member", member).indexed()
				.arg("copy", copy)
				.from(db);
	}

	private static Literal book(AnswerSource db, Unifiable<String> isbn, Unifiable<String> title) {
		return Literal.relation(VersionedWatermarkTest.class, "book")
				.arg("isbn", isbn).indexed()
				.arg("title", title)
				.from(db);
	}

	private static List<String> loansOf(AnswerSource db, String member) {
		Unifiable<String> copy = lvar();
		return Query.of(loan(db, lval(member), copy)).solve(copy)
				.map(Object::toString)
				.sorted()
				.collect(Collectors.toList());
	}

	@Test
	public void disjointRegionsOfOneRelationCommitAcrossEachOther() throws Exception {
		// the receipt the relation-grain Watermark cannot pass: both
		// transactions read and write LOAN, but different members' regions
		Transaction m1 = transaction("m1");
		Transaction m2 = transaction("m2");
		assertThat(loansOf(m1, "m1")).isEmpty();
		assertThat(loansOf(m2, "m2")).isEmpty();

		m2 = m2.asserting(Collections.singletonList(loan(null, lval("m2"), lval("c2"))));
		m2.commit();

		m1 = m1.asserting(Collections.singletonList(loan(null, lval("m1"), lval("c1"))));
		m1.commit(); // m1's region never moved — m2's commit to the SAME relation must not bounce it

		try (Transaction reader = transaction("reader")) {
			assertThat(loansOf(reader, "m1")).containsExactly("{c1}");
			assertThat(loansOf(reader, "m2")).containsExactly("{c2}");
		}
	}

	@Test
	public void anInsertIntoAPinnedEmptyRegionBounces() throws Exception {
		// the phantom case row-grain locking cannot see: no row this
		// transaction read changed — a row ARRIVED in its region
		Transaction reader = transaction("reader");
		assertThat(loansOf(reader, "m1")).isEmpty();

		try (
				Transaction mover = transaction("mover")
						.asserting(Collections.singletonList(loan(null, lval("m1"), lval("c9"))))
		) {
			mover.commit();
		}

		Transaction staged = reader.asserting(Collections.singletonList(
				loan(null, lval("m1"), lval("c1"))));
		assertThatThrownBy(() -> staged.commit())
				.describedAs("the pinned empty region gained a row — the decision stood on its absence")
				.isInstanceOf(Transaction.Conflict.class);
	}

	@Test
	public void aDeleteInsideThePinnedRegionBounces() throws Exception {
		// the delete-tolerance half of the pin pair: the deleted row is NOT
		// the region's MAX, so the old pin's MAX still matches — only the
		// row count betrays that the region moved
		try (
				Transaction w1 = transaction("w1")
						.asserting(Collections.singletonList(loan(null, lval("m1"), lval("c1"))))
		) {
			w1.commit();
		}
		try (
				Transaction w2 = transaction("w2")
						.asserting(Collections.singletonList(loan(null, lval("m1"), lval("c2"))))
		) {
			w2.commit();
		}

		Transaction reader = transaction("reader");
		assertThat(loansOf(reader, "m1")).containsExactly("{c1}", "{c2}");

		// the administrative delete — compaction's stand-in, stamped by
		// nothing: row c1 carries version 1, below the region's MAX of 2
		try (
				Connection admin = connection();
				Statement delete = admin.createStatement()
		) {
			delete.execute("DELETE FROM loan WHERE member = 'm1' AND copy = 'c1'");
		}

		Transaction staged = reader.asserting(Collections.singletonList(
				loan(null, lval("m2"), lval("c9"))));
		assertThatThrownBy(() -> staged.commit())
				.describedAs("a row left the pinned region — MAX alone cannot see it, the pair must")
				.isInstanceOf(Transaction.Conflict.class);
	}

	@Test
	public void aProtocolRetractionLandsAndBouncesThePinnedReader() throws Exception {
		try (
				Transaction w1 = transaction("w1")
						.asserting(Collections.singletonList(loan(null, lval("m1"), lval("c1"))))
		) {
			w1.commit();
		}

		Transaction reader = transaction("reader");
		assertThat(loansOf(reader, "m1")).containsExactly("{c1}");

		try (
				Transaction mover = transaction("mover")
						.retracting(Collections.singletonList(loan(null, lval("m1"), lval("c1"))))
		) {
			mover.commit(); // the delete lane lands through the door
		}

		try (Transaction after = transaction("after")) {
			assertThat(loansOf(after, "m1")).isEmpty();
		}

		Transaction staged = reader.asserting(Collections.singletonList(
				loan(null, lval("m2"), lval("c9"))));
		assertThatThrownBy(() -> staged.commit())
				.describedAs("the pinned region lost its row — the pair's count divides the pins")
				.isInstanceOf(Transaction.Conflict.class);
	}

	@Test
	public void aForkJoinSolveReadsThroughTheTransactionSafely() throws Exception {
		// the pin-lane monitor's smoke: a receipt cannot force the race, it
		// pins that the parallel driver composes with a JDBC-backed
		// transaction — both branches' reads serialize on the fetch monitor
		try (
				Transaction w = transaction("w")
						.asserting(java.util.Arrays.asList(
								loan(null, lval("m1"), lval("c1")),
								loan(null, lval("m2"), lval("c2"))))
		) {
			w.commit();
		}

		try (Transaction reader = transaction("fj-reader")) {
			Unifiable<String> member = lvar();
			Unifiable<String> copy = lvar();
			List<String> read = Query.of(loan(reader, member, copy)
					.or(loan(reader, lval("m1"), copy))).on(org.clauseway.functional.fibers.schedulers.ForkJoinScheduler::new).solve(copy)
					.map(Object::toString)
					.sorted()
					.collect(Collectors.toList());
			assertThat(read).containsExactly("{c1}", "{c1}", "{c2}");
		}
	}

	private static Literal invoice(AnswerSource db, Unifiable<String> member, Unifiable<Long> day) {
		return Literal.relation(VersionedWatermarkTest.class, "invoice")
				.arg("member", member)
				.arg("due", day)
				.from(db);
	}

	/** Reads the FD-constrained region day ∈ [1,10]; the domain rides the probe. */
	private static List<Long> earlyInvoices(AnswerSource db) {
		Unifiable<Long> day = lvar();
		return Query.of(dom(day, Longs.range(1, 10))
				.and(invoice(db, lvar(), day))).solve(day)
				.map(Term::get)
				.sorted()
				.collect(Collectors.toList());
	}

	@Test
	public void aCommitOutsideTheFdRegionDoesNotConflict() throws Exception {
		// the pushed domain narrows the PIN, not just the fetch: the pinned
		// region is "invoices with day in [1,10]", so a row landing at day 50
		// never moves its MAX — relation grain would have bounced this
		Transaction reader = transaction("fd-reader");
		assertThat(earlyInvoices(reader)).isEmpty();

		try (
				Transaction mover = transaction("mover")
						.asserting(Collections.singletonList(invoice(null, lval("m9"), lval(50L))))
		) {
			mover.commit();
		}

		Transaction staged = reader.asserting(Collections.singletonList(
				invoice(null, lval("a1"), lval(5L))));
		staged.commit(); // day 50 lies outside the pinned domain — the FD region never moved
	}

	@Test
	public void aCommitInsideTheFdRegionConflicts() throws Exception {
		Transaction reader = transaction("fd-reader");
		assertThat(earlyInvoices(reader)).isEmpty();

		try (
				Transaction mover = transaction("mover")
						.asserting(Collections.singletonList(invoice(null, lval("m9"), lval(5L))))
		) {
			mover.commit();
		}

		Transaction staged = reader.asserting(Collections.singletonList(
				invoice(null, lval("a1"), lval(7L))));
		assertThatThrownBy(() -> staged.commit())
				.describedAs("day 5 lies inside the pinned domain — the FD region moved")
				.isInstanceOf(Transaction.Conflict.class);
	}

	@Test
	public void aTableWithoutTheVersionColumnRefusesLoudly() {
		Transaction transaction = transaction("books");
		Unifiable<String> title = lvar();
		assertThatThrownBy(() -> Query.of(book(transaction, lval("978-0"), title)).solve(title)
				.collect(Collectors.toList()))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("book")
				.hasMessageContaining("version");
	}
}
