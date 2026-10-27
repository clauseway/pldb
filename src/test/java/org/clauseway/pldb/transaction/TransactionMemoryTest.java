package org.clauseway.pldb.transaction;

// ABOUTME: The Transaction over the in-memory simulated serialization: the shared
// ABOUTME: cell is the one history, snapshots are values, the CAS is the commit lock.

import org.clauseway.logic.solving.Query;
import org.clauseway.pldb.relations.Relation;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.clauseway.logic.unification.terms.LVal.lval;
import static org.clauseway.logic.unification.terms.LVar.lvar;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import org.clauseway.logic.unification.terms.Unifiable;
import org.clauseway.pldb.AnswerSource;
import org.clauseway.pldb.inmemory.SharedDatabase;
import org.clauseway.pldb.relations.literals.External;
import org.clauseway.pldb.relations.literals.Literal;
import org.junit.Test;

public class TransactionMemoryTest {

	private static External<?> person(AnswerSource db, Unifiable<Integer> id, Unifiable<String> name) {
		return Literal.relation(TransactionMemoryTest.class, "person")
				.arg("id", id).indexed()
				.arg("name", name)
				.from(db);
	}

	private static External<?> book(AnswerSource db, Unifiable<String> isbn, Unifiable<String> title) {
		return Literal.relation(TransactionMemoryTest.class, "book")
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

	@Test
	public void commitLandsStagedFactsForTheNextTransaction() throws Exception {
		SharedDatabase store = SharedDatabase.empty();
		Transaction writer = Transaction.recording(store.open("w"))
				.asserting(Collections.singletonList(person(null, lval(1), lval("Ada"))));
		assertThat(names(writer)).containsExactly("{Ada}");
		writer.commit();

		try (Transaction reader = Transaction.recording(store.open("r"))) {
			assertThat(names(reader)).containsExactly("{Ada}");
		}
	}

	private static SharedDatabase seededWithAda() throws Transaction.Conflict {
		SharedDatabase store = SharedDatabase.empty();
		Transaction.recording(store.open("seed"))
				.asserting(Collections.singletonList(person(null, lval(1), lval("Ada"))))
				.commit();
		return store;
	}

	@Test
	public void aStagedRetractionShadowsOwnReadsAndAbandonmentLeavesNoTrace() throws Exception {
		SharedDatabase store = seededWithAda();
		Transaction tx = Transaction.recording(store.open("tx"));
		assertThat(names(tx)).containsExactly("{Ada}");

		Transaction staged = tx.retracting(Collections.singletonList(
				person(null, lval(1), lval("Ada"))));
		assertThat(names(staged))
				.describedAs("the buffer's own reads stop seeing the staged removal")
				.isEmpty();

		try (Transaction reader = Transaction.recording(store.open("r"))) {
			assertThat(names(reader))
					.describedAs("an abandoned retraction leaves no trace")
					.containsExactly("{Ada}");
		}
	}

	@Test
	public void aCommittedRetractionRemovesForTheNextTransaction() throws Exception {
		SharedDatabase store = seededWithAda();
		Transaction.recording(store.open("tx"))
				.retracting(Collections.singletonList(person(null, lval(1), lval("Ada"))))
				.commit();

		try (Transaction reader = Transaction.recording(store.open("r"))) {
			assertThat(names(reader)).isEmpty();
		}
	}

	@Test
	public void aRetractionMovesTheRelationsWorld() throws Exception {
		SharedDatabase store = seededWithAda();
		Transaction reader = Transaction.recording(store.open("reader"));
		assertThat(names(reader)).containsExactly("{Ada}");

		Transaction.recording(store.open("mover"))
				.retracting(Collections.singletonList(person(null, lval(1), lval("Ada"))))
				.commit();

		assertThatThrownBy(() -> reader.asserting(Collections.singletonList(
				book(null, lval("i1"), lval("Tar Pit")))).commit())
				.describedAs("the pinned person region lost a row — the retraction divides pins")
				.isInstanceOf(Transaction.Conflict.class);
	}

	/** The delta is a signed set: a fact staged and taken back in one transaction lands nothing, either way round. */
	@Test
	public void assertingAndRetractingOneFactCancelsOut() throws Exception {
		SharedDatabase store = seededWithAda();

		Transaction.recording(store.open("a"))
				.asserting(Collections.singletonList(book(null, lval("i1"), lval("Tar Pit"))))
				.retracting(Collections.singletonList(book(null, lval("i1"), lval("Tar Pit"))))
				.commit();
		Transaction.recording(store.open("b"))
				.retracting(Collections.singletonList(person(null, lval(1), lval("Ada"))))
				.asserting(Collections.singletonList(person(null, lval(1), lval("Ada"))))
				.commit();

		Transaction reader = Transaction.recording(store.open("reader"));
		assertThat(names(reader)).containsExactly("{Ada}");
		assertThat(Query.of(book(reader, lvar(), lvar())).solve(lval(true)).count()).isZero();
	}

	private static External<?> loanOf(AnswerSource db, Unifiable<Integer> id, Unifiable<String> copy) {
		return Literal.relation(TransactionMemoryTest.class, "loan")
				.arg("loanId", id).indexed()
				.arg("copy", copy)
				.from(db);
	}

	private static External<?> returnedOf(AnswerSource db, Unifiable<Integer> id) {
		return Literal.relation(TransactionMemoryTest.class, "returned")
				.arg("loanId", id).indexed()
				.from(db);
	}

	@Test
	public void aSelectedClusterRetractsThroughTheDoor() throws Exception {
		// the retract arc's face, composed instead of built: the question
		// selects the closed cluster, the door removes it, the commit
		// certifies the reads the closure stood on
		SharedDatabase store = SharedDatabase.empty();
		Transaction.recording(store.open("seed"))
				.asserting(loanOf(null, lval(1), lval("c1")), returnedOf(null, lval(1)))
				.commit();

		RecordingTransaction tx = Transaction.recording(store.open("compact"));
		Unifiable<Integer> id = lvar();
		Unifiable<String> copy = lvar();
		Query closed = Query.of(loanOf(tx, id, copy).and(returnedOf(tx, id)));
		tx.retracting(closed.select(loanOf(null, id, copy).facts()))
				.retracting(closed.select(returnedOf(null, id).facts()))
				.commit();

		try (Transaction reader = Transaction.recording(store.open("after"))) {
			Unifiable<String> c = lvar();
			assertThat(Query.of(loanOf(reader, lvar(), c)).solve(c).count()).isZero();
			Unifiable<Integer> r = lvar();
			assertThat(Query.of(returnedOf(reader, r)).solve(r).count()).isZero();
		}
	}

	@Test
	public void writeSkewOnOneRelationMeetsTheConflict() throws Exception {
		SharedDatabase store = SharedDatabase.empty();
		Transaction first = Transaction.recording(store.open("first"));
		Transaction second = Transaction.recording(store.open("second"));
		assertThat(names(first)).isEmpty();
		assertThat(names(second)).isEmpty();

		first = first.asserting(Collections.singletonList(
				person(null, lval(1), lval("Ada"))));
		second = second.asserting(Collections.singletonList(
				person(null, lval(2), lval("Alan"))));

		first.commit();
		Transaction loser = second;
		assertThatThrownBy(loser::commit).isInstanceOf(Transaction.Conflict.class);

		try (Transaction reader = Transaction.recording(store.open("r"))) {
			assertThat(names(reader)).containsExactly("{Ada}");
		}
	}

	@Test
	public void disjointRelationsCommitWithoutConflict() throws Exception {
		SharedDatabase store = SharedDatabase.empty();
		try (
				Transaction seed = Transaction.recording(store.open("seed"))
						.asserting(Collections.singletonList(person(null, lval(1), lval("Ada"))))
						.asserting(Collections.singletonList(book(null, lval("978-0"), lval("SICP"))))
		) {
			seed.commit();
		}

		Transaction bookWriter = Transaction.recording(store.open("books"));
		Transaction personWriter = Transaction.recording(store.open("people"));
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
	public void aForeignDisjointCommitBetweenReadsDoesNotBounce() throws Exception {
		// the disjoint receipt's interleaved variant: the person region's
		// FIRST touch happens AFTER the book commit moved the world — the
		// pin carries the captured generations, the global fast path fails,
		// and the per-relation compare must still prove person unmoved
		SharedDatabase store = SharedDatabase.empty();
		Transaction people = Transaction.recording(store.open("people"));

		try (
				Transaction books = Transaction.recording(store.open("books"))
						.asserting(Collections.singletonList(book(null, lval("978-0"), lval("SICP"))))
		) {
			books.commit();
		}

		assertThat(names(people)).isEmpty();
		Transaction staged = people.asserting(Collections.singletonList(
				person(null, lval(1), lval("Ada"))));
		staged.commit(); // only book moved — the disjoint person write must land
	}

	@Test
	public void readsAreStableAcrossAForeignCommit() throws Exception {
		// the snapshot property REST cannot promise and SQL buys with MVCC:
		// a commit landing between my open and my read must not appear
		SharedDatabase store = SharedDatabase.empty();
		Transaction reader = Transaction.recording(store.open("reader"));

		try (
				Transaction writer = Transaction.recording(store.open("writer"))
						.asserting(Collections.singletonList(person(null, lval(1), lval("Ada"))))
		) {
			writer.commit();
		}

		assertThat(names(reader))
				.describedAs("the snapshot was taken at open, not at first read")
				.isEmpty();
		reader.close();
	}

	@Test
	public void anAbandonedTransactionLeavesNoTrace() throws Exception {
		SharedDatabase store = SharedDatabase.empty();
		try (
				Transaction abandoned = Transaction.recording(store.open("a"))
						.asserting(Collections.singletonList(person(null, lval(1), lval("Ada"))))
		) {
			assertThat(names(abandoned)).containsExactly("{Ada}");
		}
		try (Transaction reader = Transaction.recording(store.open("r"))) {
			assertThat(names(reader)).isEmpty();
		}
	}
}
