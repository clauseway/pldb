package org.clauseway.pldb.transaction;

// ABOUTME: The premise: a client's earlier pinned reads carried into a later commit —
// ABOUTME: certified beside the transaction's own ledger, refusing if that world moved.

import org.clauseway.logic.solving.Query;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.clauseway.logic.unification.terms.LVal.lval;
import static org.clauseway.logic.unification.terms.LVar.lvar;

import java.util.Collections;
import java.util.stream.Collectors;
import org.clauseway.logic.unification.terms.Unifiable;
import org.clauseway.pldb.AnswerSource;
import org.clauseway.pldb.inmemory.SharedDatabase;
import org.clauseway.pldb.relations.Literal;
import org.junit.Test;

public class PremiseTest {

	private static Literal person(AnswerSource db, Unifiable<Integer> id, Unifiable<String> name) {
		return Literal.relation(PremiseTest.class, "person")
				.arg("id", id).indexed()
				.arg("name", name)
				.from(db);
	}

	private static Literal book(AnswerSource db, Unifiable<String> isbn, Unifiable<String> title) {
		return Literal.relation(PremiseTest.class, "book")
				.arg("isbn", isbn).indexed()
				.arg("title", title)
				.from(db);
	}

	private static void solveNames(AnswerSource db) {
		Unifiable<String> name = lvar();
		Query.of(person(db, lvar(), name)).solve(name).collect(Collectors.toList());
	}

	private static void solveTitles(AnswerSource db) {
		Unifiable<String> title = lvar();
		Query.of(book(db, lvar(), title)).solve(title).collect(Collectors.toList());
	}

	@Test
	public void theFootprintExposesEveryReadIncludingTheEmptyOne() throws Exception {
		SharedDatabase store = SharedDatabase.empty();
		AbstractTransaction.over(store.open("seed"))
				.asserting(Collections.singletonList(person(null, lval(1), lval("Ada"))))
				.commit();

		try (Simulated get = AbstractTransaction.over(store.open("get"))) {
			solveNames(get);
			solveTitles(get);
			assertThat(get.footprint().pins().keySet().stream()
					.map(region -> region.getRelation().getName())
					.distinct())
					.describedAs("the populated read AND the empty one both pin their regions")
					.containsExactlyInAnyOrder("person", "book");
		}
	}

	@Test
	public void aFreshPremiseLandsThroughACommitThatNeverReadIt() throws Exception {
		SharedDatabase store = SharedDatabase.empty();
		Footprint premise;
		try (Simulated get = AbstractTransaction.over(store.open("get"))) {
			solveNames(get);
			premise = get.footprint();
		}

		// the pure-premise posture: the committing transaction reads NOTHING —
		// person is certified only because the client's premise carried it
		AbstractTransaction.over(store.open("post"))
				.requiring(premise)
				.asserting(Collections.singletonList(book(null, lval("i1"), lval("Tar Pit"))))
				.commit();
	}

	@Test
	public void aPremiseOverlappingTheOwnFootprintComposesWhenTheWorldsAgree() throws Exception {
		// premise and the commit's own ledger both read person, from
		// snapshots of ONE world: the union sees equal pins on the shared
		// region and the commit lands
		SharedDatabase store = SharedDatabase.empty();
		Footprint premise;
		try (Simulated get = AbstractTransaction.over(store.open("get"))) {
			solveNames(get);
			premise = get.footprint();
		}

		Simulated post = AbstractTransaction.over(store.open("post")).requiring(premise);
		solveNames(post);
		post
				.asserting(Collections.singletonList(book(null, lval("i1"), lval("Tar Pit"))))
				.commit();
	}

	@Test
	public void aPremiseOverlappingTheOwnFootprintRefusesAcrossWorlds() throws Exception {
		// the same overlap after person MOVED: the commit's own snapshot
		// read a newer person than the premise did — the union meets two
		// pins on one region and refuses at composition, before certify
		SharedDatabase store = SharedDatabase.empty();
		Footprint premise;
		try (Simulated get = AbstractTransaction.over(store.open("get"))) {
			solveNames(get);
			premise = get.footprint();
		}

		AbstractTransaction.over(store.open("mover"))
				.asserting(Collections.singletonList(person(null, lval(2), lval("Alan"))))
				.commit();

		Simulated post = AbstractTransaction.over(store.open("post")).requiring(premise);
		solveNames(post);
		assertThatThrownBy(() -> post
				.asserting(Collections.singletonList(book(null, lval("i1"), lval("Tar Pit"))))
				.commit())
				.describedAs("the premise and the own read saw different person worlds")
				.isInstanceOf(Transaction.Conflict.class);
	}

	@Test
	public void aStalePremiseRefusesTheCommitEvenThoughItsOwnReadsHold() throws Exception {
		SharedDatabase store = SharedDatabase.empty();
		Footprint premise;
		try (Simulated get = AbstractTransaction.over(store.open("get"))) {
			solveNames(get);
			premise = get.footprint();
		}

		AbstractTransaction.over(store.open("mover"))
				.asserting(Collections.singletonList(person(null, lval(2), lval("Alan"))))
				.commit();

		assertThatThrownBy(() -> AbstractTransaction.over(store.open("post"))
				.requiring(premise)
				.asserting(Collections.singletonList(book(null, lval("i1"), lval("Tar Pit"))))
				.commit())
				.describedAs("the world the client decided against has moved — re-read, re-solve")
				.isInstanceOf(Transaction.Conflict.class);
	}
}
