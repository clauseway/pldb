package org.clauseway.pldb.inmemory;

// ABOUTME: The Call-native store's receipts: buckets serve ground probes, null and
// ABOUTME: wide rows key honestly, duplicate images ⊕-fold, values fork persistent.

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.clauseway.logic.nogoods.Exclusion.exclude;
import static org.clauseway.logic.unification.terms.LVal.lval;
import static org.clauseway.logic.unification.terms.LVar.lvar;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import org.clauseway.functional.Nothing;
import org.clauseway.functional.fibers.Fiber;
import org.clauseway.functional.fibers.schedulers.BreadthFirstScheduler;
import org.clauseway.logic.solving.Condition;
import org.clauseway.logic.solving.Call;
import org.clauseway.logic.tabling.table.Table;
import org.clauseway.logic.unification.terms.Any;
import org.clauseway.logic.unification.terms.Reified;
import org.clauseway.logic.unification.terms.Term;
import org.clauseway.logic.unification.terms.Unifiable;
import org.clauseway.pldb.GoalProducer;
import org.clauseway.logic.solving.Answer;
import org.clauseway.pldb.relations.Answers;
import org.clauseway.pldb.relations.Literal;
import org.clauseway.pldb.relations.Relation;
import org.junit.Test;

public class AnswerStoreTest {

	private static final Relation LOAN = Literal.relation(AnswerStoreTest.class, "loan")
			.arg("member", lvar()).indexed()
			.arg("copy", lvar())
			.from(null)
			.getRel();

	private static Answer<Relation> row(Object member, Object copy) {
		return Answers.answer(LOAN, Arrays.asList(member, copy));
	}

	private static Call<Relation> probe(Term<?> member, Term<?> copy) {
		return Call.of(LOAN, Answers.image(member, copy));
	}

	private static List<String> images(Iterable<Answer<Relation>> answers) {
		return StreamSupport.stream(answers.spliterator(), false)
				.map(answer -> answer.getReified().toString())
				.collect(Collectors.toList());
	}

	@Test
	public void aGroundIndexedProbeServesItsBucket() {
		AnswerStore store = AnswerStore.empty()
				.with(LOAN, row("m1", "c1"))
				.with(LOAN, row("m1", "c2"))
				.with(LOAN, row("m2", "c3"));

		assertThat(images(store.answers(probe(lval("m1"), Any.of(1)))))
				.containsExactly("{({m1}, {c1})}", "{({m1}, {c2})}");
		assertThat(store.estimate(probe(lval("m1"), Any.of(1)))).isEqualTo(2);
		assertThat(store.estimate(probe(Any.of(0), Any.of(1)))).isEqualTo(3);
	}

	private static Literal loanFact(String member, String copy) {
		return Literal.relation(AnswerStoreTest.class, "loan")
				.arg("member", lval(member)).indexed()
				.arg("copy", lval(copy))
				.from(null);
	}

	@Test
	public void retractingRemovesTheFactWholeFromRowsAndBuckets() {
		AnswerStore store = AnswerStore.empty()
				.with(LOAN, row("m1", "c1"))
				.with(LOAN, row("m1", "c2"));

		AnswerStore after = store.retracting(loanFact("m1", "c1"));
		assertThat(images(after.answers(probe(lval("m1"), Any.of(1)))))
				.describedAs("the bucket path serves post-removal — the index forgot the image")
				.containsExactly("{({m1}, {c2})}");
		assertThat(after.estimate(probe(lval("m1"), Any.of(1)))).isEqualTo(1);

		assertThat(images(store.answers(probe(lval("m1"), Any.of(1)))))
				.describedAs("the ancestor value keeps answering as before")
				.hasSize(2);
	}

	@Test
	public void retractingAnAbsentFactIsANoOp() {
		AnswerStore store = AnswerStore.empty().with(LOAN, row("m1", "c1"));

		AnswerStore after = store.retracting(loanFact("m9", "c9"));
		assertThat(images(after.answers(probe(Any.of(0), Any.of(1))))).hasSize(1);
	}

	@Test
	public void aWideRowRefusesTheStrictWriteDoor() {
		// with() is the seam's permissive dock; the Writer face is the
		// strict domain door — wide cells refuse by relation and column
		Answer<Relation> wide = Answer.of(LOAN,
				Answers.image(Any.of(0), lval("c9")), Condition.ONE);

		assertThatThrownBy(() -> AnswerStore.empty()
				.asserting(Collections.singletonList(wide)))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("loan")
				.hasMessageContaining("member");
	}

	@Test
	public void aGuardedRowRefusesUntilTheGuardDropsExplicitly() {
		Condition guarded = forbidding("m9");
		Answer<Relation> row = Answer.of(LOAN, row("m1", "c1").getReified(), guarded);

		assertThatThrownBy(() -> AnswerStore.empty()
				.asserting(Collections.singletonList(row)))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("unconditional");

		AnswerStore landed = AnswerStore.empty()
				.asserting(Collections.singletonList(row.unconditional()));
		assertThat(images(landed.answers(probe(lval("m1"), Any.of(1))))).hasSize(1);
	}

	@Test
	public void aNonIndexedBoundPositionFilters() {
		AnswerStore store = AnswerStore.empty()
				.with(LOAN, row("m1", "c1"))
				.with(LOAN, row("m1", "c2"));

		assertThat(images(store.answers(probe(lval("m1"), lval("c2")))))
				.containsExactly("{({m1}, {c2})}");
	}

	@Test
	public void nullIsAnHonestBucketKey() {
		AnswerStore store = AnswerStore.empty()
				.with(LOAN, row(null, "c1"))
				.with(LOAN, row("m1", "c2"));

		assertThat(images(store.answers(probe(lval(null), Any.of(1)))))
				.containsExactly("{({null}, {c1})}");
	}

	@Test
	public void aRowFreeAtAnIndexedColumnMatchesEveryProbe() {
		AnswerStore store = AnswerStore.empty()
				.with(LOAN, row("m1", "c1"))
				.with(LOAN, Answer.of(LOAN,
						Answers.image(Any.of(0), lval("c9")),
						Condition.ONE));

		assertThat(images(store.answers(probe(lval("m2"), Any.of(1)))))
				.describedAs("the wide row lives in the wildcard set — every member probe sees it")
				.containsExactly("{(_.0, {c9})}");
	}

	@Test
	public void aDuplicateImageFoldsItsConditionInsteadOfDuplicating() {
		AnswerStore store = AnswerStore.empty()
				.with(LOAN, row("m1", "c1"))
				.with(LOAN, row("m1", "c1"));

		Iterable<Answer<Relation>> answers = store.answers(probe(lval("m1"), Any.of(1)));
		assertThat(images(answers)).hasSize(1);
		assertThat(answers.iterator().next().getCondition()).isEqualTo(Condition.ONE);
	}

	/** A REAL condition, minted by a guarded produce: ¬(member ≡ forbidden). */
	private static Condition forbidding(String forbidden) {
		Unifiable<Object> member = lvar();
		Unifiable<Object> copy = lvar();
		GoalProducer guarded = GoalProducer.of(LOAN,
				exclude(member.unifies(forbidden)), Arrays.asList(member, copy), Table.empty());
		List<Answer<Relation>> delivered = new ArrayList<>();
		new BreadthFirstScheduler<>(guarded.produce(probe(Any.of(0), Any.of(1)), answer -> {
			delivered.add(answer);
			return Fiber.done(Nothing.nothing());
		})).get();
		return delivered.get(0).getCondition();
	}

	@Test
	public void aConditionalAnswerLandsAndKeepsItsCondition() {
		// the ground-pool refusal dies here: the store speaks the seam's
		// whole entry shape, conditions included
		Condition guarded = forbidding("m9");
		AnswerStore store = AnswerStore.empty()
				.with(LOAN, Answer.of(LOAN, row("m1", "c1").getReified(), guarded));

		assertThat(store.answers(probe(lval("m1"), Any.of(1))).iterator().next().getCondition())
				.isEqualTo(guarded);
	}

	@Test
	public void oneImageUnderTwoGuardsFoldsToTheirDisjunction() {
		// the c-table union law with REAL conditions: membership holds if
		// EITHER derivation's guard does — the folded condition is A ⊕ B,
		// not A, not B, not ONE
		Condition notM8 = forbidding("m8");
		Condition notM9 = forbidding("m9");
		assertThat(notM8).isNotEqualTo(notM9);

		Reified<?> image = row("m1", "c1").getReified();
		AnswerStore store = AnswerStore.empty()
				.with(LOAN, Answer.of(LOAN, image, notM8))
				.with(LOAN, Answer.of(LOAN, image, notM9));

		Iterable<Answer<Relation>> answers = store.answers(probe(lval("m1"), Any.of(1)));
		assertThat(images(answers)).hasSize(1);
		Condition folded = answers.iterator().next().getCondition();
		assertThat(folded).isEqualTo(Condition.RING.plus(notM8, notM9));
		assertThat(folded).isNotEqualTo(notM8);
		assertThat(folded).isNotEqualTo(notM9);
		assertThat(folded).isNotEqualTo(Condition.ONE);
	}

	@Test
	public void aNarrowInsertServesTheWideRead() {
		// membership is direction-free: rows fetched under narrow probes are
		// rows of the RELATION — the wide probe sees them all; only coverage
		// (beside the store) may claim it saw everything
		AnswerStore store = AnswerStore.empty()
				.with(LOAN, row("m1", "c1"))
				.with(LOAN, row("m2", "c2"));

		assertThat(images(store.answers(probe(Any.of(0), Any.of(1)))))
				.containsExactly("{({m1}, {c1})}", "{({m2}, {c2})}");
	}

	@Test
	public void overlappingWideImagesKeepTheirOwnGuards() {
		// loan(m1,_) under A and loan(_,c3) under B: their common instance
		// loan(m1,c3) is claimed by BOTH — the store delivers both rows with
		// their OWN conditions, and the instance's A ⊕ B emerges at delivery
		// (either branch restates); no cross-image fold, ever — images are
		// distinct claims, not duplicate derivations
		Condition a = forbidding("m8");
		Condition b = forbidding("m9");
		// Any numbering is by OCCURRENCE, not position: _.0 is the first free
		// met walking the image, wherever it sits — loan(5,x) reifies to
		// ({5}, _.0). It cannot be positional, because the number also
		// carries coupling: loan(x,x) is (_.0, _.0). The store never reads
		// the number (only free-vs-ground), so both images below are
		// realistic probe shapes.
		Reified<?> memberWide = Answers.image(lval("m1"), Any.of(0));
		Reified<?> copyWide = Answers.image(Any.of(0), lval("c3"));
		AnswerStore store = AnswerStore.empty()
				.with(LOAN, Answer.of(LOAN, memberWide, a))
				.with(LOAN, Answer.of(LOAN, copyWide, b));

		Iterable<Answer<Relation>> overlap = store.answers(probe(lval("m1"), lval("c3")));
		assertThat(images(overlap)).containsExactly("{({m1}, _.0)}", "{(_.0, {c3})}");
		Iterator<Answer<Relation>> both = overlap.iterator();
		assertThat(both.next().getCondition()).isEqualTo(a);
		assertThat(both.next().getCondition()).isEqualTo(b);

		assertThat(images(store.answers(probe(lval("m9"), lval("c3")))))
				.describedAs("outside the member-wide claim, only the copy-wide row answers")
				.containsExactly("{(_.0, {c3})}");
	}

	private static final Relation PAIR = Literal.relation(AnswerStoreTest.class, "pair")
			.arg("a", lvar()).indexed()
			.arg("b", lvar()).indexed()
			.from(null)
			.getRel();

	private static Answer<Relation> pair(Object a, Object b) {
		return Answers.answer(PAIR, Arrays.asList(a, b));
	}

	private static Call<Relation> pairProbe(Term<?> a, Term<?> b) {
		return Call.of(PAIR, Answers.image(a, b));
	}

	@Test
	public void twoGroundIndexedPositionsIntersectTheirBuckets() {
		// the narrowing path: each column's bucket over-approximates alone —
		// only their intersection names the row, and the estimate is exact
		// on it
		AnswerStore store = AnswerStore.empty()
				.with(PAIR, pair("a1", "b1"))
				.with(PAIR, pair("a1", "b2"))
				.with(PAIR, pair("a2", "b1"));

		assertThat(images(store.answers(pairProbe(lval("a1"), lval("b1")))))
				.containsExactly("{({a1}, {b1})}");
		assertThat(store.estimate(pairProbe(lval("a1"), lval("b1")))).isEqualTo(1);
	}

	@Test
	public void anEmptyIntersectionAnswersNothingAndEstimatesZero() {
		// both buckets are non-empty; their intersection is not "all rows"
		// (the null sentinel) but the honest empty set
		AnswerStore store = AnswerStore.empty()
				.with(PAIR, pair("a1", "b1"))
				.with(PAIR, pair("a2", "b2"));

		assertThat(store.answers(pairProbe(lval("a1"), lval("b2")))).isEmpty();
		assertThat(store.estimate(pairProbe(lval("a1"), lval("b2")))).isEqualTo(0);
	}

	@Test
	public void aWildcardSurvivesTheIntersection() {
		// a row free at one indexed column rides that column's wildcard set
		// INTO the intersection: pair(a9, _) answers any b probe under a9
		AnswerStore store = AnswerStore.empty()
				.with(PAIR, pair("a1", "b1"))
				.with(PAIR, Answer.of(PAIR,
						Answers.image(lval("a9"), Any.of(0)),
						Condition.ONE));

		assertThat(images(store.answers(pairProbe(lval("a9"), lval("b5")))))
				.containsExactly("{({a9}, _.0)}");
		assertThat(store.answers(pairProbe(lval("a1"), lval("b5")))).isEmpty();
	}

	private static final Relation FLAGLESS = Literal.relation(AnswerStoreTest.class, "flagless")
			.arg("member", lvar())
			.arg("copy", lvar())
			.from(null)
			.getRel();

	@Test
	public void anUnflaggedRelationFullScansCorrectly() {
		// no indexed() declarations on the relation — the store consults the
		// access-pattern contract and finds none: every probe walks all rows
		AnswerStore store = AnswerStore.empty()
				.with(FLAGLESS, Answers.answer(FLAGLESS, Arrays.asList("m1", "c1")))
				.with(FLAGLESS, Answers.answer(FLAGLESS, Arrays.asList("m2", "c2")));

		Call<Relation> bound = Call.of(FLAGLESS,
				Answers.image((Term<?>) lval("m1"), Any.of(0)));
		assertThat(images(store.answers(bound))).hasSize(1);
		assertThat(store.estimate(bound))
				.describedAs("no declared pattern — the estimate is the full scan's")
				.isEqualTo(2);
	}

	@Test
	public void insertsForkTheValueAndAncestorsKeepAnswering() {
		AnswerStore before = AnswerStore.empty().with(LOAN, row("m1", "c1"));
		AnswerStore after = before.with(LOAN, row("m1", "c2"));

		assertThat(images(before.answers(probe(lval("m1"), Any.of(1))))).hasSize(1);
		assertThat(images(after.answers(probe(lval("m1"), Any.of(1))))).hasSize(2);
	}
}
