package org.clauseway.pldb.constraints;

// ABOUTME: The parking posted table's receipts: ground rows match the sync oracle,
// ABOUTME: conditional rows impose at commit, Any rows admit everything and skip supports.

import org.clauseway.logic.solving.Query;
import static org.assertj.core.api.Assertions.assertThat;
import static org.clauseway.logic.nogoods.Exclusion.exclude;
import static org.clauseway.logic.unification.terms.LVal.lval;
import static org.clauseway.logic.unification.terms.LVar.lvar;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.clauseway.functional.Nothing;
import org.clauseway.functional.fibers.Cont;
import org.clauseway.functional.fibers.Fiber;
import org.clauseway.functional.fibers.schedulers.BreadthFirstScheduler;
import org.clauseway.functional.tuples.Tuple;
import org.clauseway.logic.constraints.Posting;
import org.clauseway.logic.constraints.store.Constraint;
import org.clauseway.logic.constraints.store.Theory;
import org.clauseway.logic.goals.Goal;
import org.clauseway.logic.goals.Knowledge;
import org.clauseway.logic.solving.Call;
import org.clauseway.logic.tabling.table.Table;
import org.clauseway.logic.unification.terms.Any;
import org.clauseway.logic.unification.terms.Unifiable;
import org.clauseway.pldb.AnswerSource;
import org.clauseway.pldb.GoalProducer;
import org.clauseway.pldb.inmemory.AnswerStore;
import org.clauseway.logic.solving.Answer;
import org.clauseway.pldb.relations.Answers;
import org.clauseway.pldb.relations.Literal;
import org.clauseway.pldb.relations.Property;
import org.clauseway.pldb.relations.Relation;
import org.junit.Test;

public class TableParkingPropagatorTest {

	private static Literal r(AnswerSource db, Unifiable<Integer> item, Unifiable<String> tag) {
		return Literal.relation(TableParkingPropagatorTest.class, "r")
				.arg("item", item).indexed()
				.arg("tag", tag).indexed()
				.from(db);
	}

	private static Relation rRel() {
		return r(null, lvar(), lvar()).getRel();
	}

	private static final Property<Integer> item = Property.of("item");
	private static final Property<String> tag = Property.of("tag");

	private static final AnswerStore db = AnswerStore.empty()
			.asserting(Arrays.asList(
					r(null, lval(1), lval("a")),
					r(null, lval(2), lval("b")),
					r(null, lval(3), lval("c"))));

	/** The reference relation as a rule: the body is the db lookup over the heads. */
	private static Posting posted(Unifiable<Integer> i, Unifiable<String> t) {
		return Literal.relation(TableParkingPropagatorTest.class, "rr")
				.arg("item", i)
				.arg("tag", t)
				.solving(r(db, i, t)).posted();
	}

	/** The exact answers for {@code out}, rendered and sorted (order is the scheduler's). */
	private static List<String> answers(Goal goal, Unifiable<?> out) {
		return Query.of(goal).solve(out)
				.map(Object::toString)
				.sorted()
				.collect(Collectors.toList());
	}

	/** A goal that runs assertions against the live package and succeeds. */
	private static Goal probe(Consumer<Knowledge> check) {
		return p -> {
			check.accept(p);
			return Cont.just(p);
		};
	}

	@Test
	public void aGroundPostIsAMembershipCheck() {
		assertThat(answers(posted(lval(2), lval("b")), lvar())).containsExactly("_.0");
		assertThat(answers(posted(lval(2), lval("c")), lvar())).isEmpty();
	}

	@Test
	public void anEmptyExtensionFails() {
		assertThat(answers(posted(lval(9), lvar()), lvar())).isEmpty();
	}

	@Test
	public void aSingletonCandidateCollapsesToBindings() {
		Unifiable<String> viaParking = lvar();
		Unifiable<String> viaSync = lvar();
		assertThat(answers((Goal) posted(lval(2), viaParking), viaParking))
				.isEqualTo(answers(r(db, lval(2), viaSync).posted(), viaSync))
				.containsExactly("{b}");
	}

	@Test
	public void aGroundProbeReDerivesAndGuardsEvaluateAtTheSource() {
		// the body runs FROM THE KEY: a fresh ground probe re-derives with
		// ground args, so the guard evaluates at production — trivially true
		// at 1 (condition ONE, subsumed), a failed branch at 2 (empty
		// extension, fail). The commit path is for REPLAYED conditions
		assertThat(answers(guardedPosted(lval(1), lval("x")), lvar())).containsExactly("_.0");
		assertThat(answers(guardedPosted(lval(2), lval("x")), lvar())).isEmpty();
	}

	/** A rule ignoring its tag: one row under the guard item ≠ 2. */
	private static Posting guardedPosted(Unifiable<Integer> i, Unifiable<String> t) {
		return Literal.relation(TableParkingPropagatorTest.class, "guardedR")
				.arg("item", i)
				.arg("tag", t)
				.solving(exclude(i.unifies(2))).posted();
	}

	@Test
	public void aReplayedConditionImposesAtCommit() {
		// the wide post discharges its one CONDITIONAL entry — the answer is
		// the watched arg under the guard, verbatim. The later ground probes
		// replay the sealed entry: the condition is not re-derived, it is
		// imposed by the discharge restate, and decides at the anchor
		Unifiable<Integer> x = lvar();
		assertThat(answers(guardedPosted(x, lvar()), x))
				.containsExactly("_.0 : ¬(_.0 ≡ {2})");
		assertThat(answers(guardedPosted(lval(1), lval("x")), lvar())).containsExactly("_.0");
		assertThat(answers(guardedPosted(lval(2), lval("x")), lvar())).isEmpty();
	}

	@Test
	public void aMultiConjunctDischargeForksTheConde() {
		// two guards on the same free image fold to {≠2}⊕{≠3}. The free post
		// discharges one branch per conjunct — the conditions attach to the
		// WATCHED arg (a fresh out var projects none of them and reads as a
		// bare _.0). At a GROUND probe the replayed conjuncts restate at the
		// ground anchor during delivery: a satisfied guard discharges and the
		// re-captured answer is UNCONDITIONAL, so the branches fold to one
		// entry — one answer at 4 (both guards pass), one at 2 ({≠3} survives)
		Unifiable<Integer> x = lvar();
		assertThat(answers(twoGuardsPosted(x, lvar()), x))
				.containsExactlyInAnyOrder("_.0 : ¬(_.0 ≡ {2})", "_.0 : ¬(_.0 ≡ {3})");
		assertThat(answers(twoGuardsPosted(lval(4), lval("x")), lvar())).containsExactly("_.0");
		assertThat(answers(twoGuardsPosted(lval(2), lval("x")), lvar())).containsExactly("_.0");
	}

	private static Posting twoGuardsPosted(Unifiable<Integer> i, Unifiable<String> t) {
		return Literal.relation(TableParkingPropagatorTest.class, "twoGuardsR")
				.arg("item", i)
				.arg("tag", t)
				.solving(exclude(i.unifies(2)).or(exclude(i.unifies(3)))).posted();
	}

	@Test
	public void aWideRowDischargesByRestateLeavingTheColumnFree() {
		// the body pins the item and says nothing about the tag: the entry is
		// (1, Any) — commit unifies the item and couples the tag to a fresh
		// existential, so the item is decided and the tag stays open
		Unifiable<Integer> x = lvar();
		assertThat(answers(widePosted(x, lvar()), x)).containsExactly("{1}");
		Unifiable<String> tagFree = lvar();
		assertThat(answers(widePosted(lvar(), tagFree), tagFree)).containsExactly("_.0");
	}

	private static Posting widePosted(Unifiable<Integer> i, Unifiable<String> t) {
		return Literal.relation(TableParkingPropagatorTest.class, "wideR")
				.arg("item", i)
				.arg("tag", t)
				.solving(i.unifies(1)).posted();
	}

	@Test
	public void aCoupledRowRefusesMismatchedGroundArgs() {
		// the body couples the columns: the one entry is (Any₀, Any₀) — the
		// diagonal, not the plane. A ground post off the diagonal must fail;
		// on it, discharge — never subsumed on the uncoupled shadow
		assertThat(answers(diagonalPosted(lval("v"), lval("v")), lvar())).containsExactly("_.0");
		assertThat(answers(diagonalPosted(lval("v"), lval("w")), lvar())).isEmpty();
		// wide and off/on the diagonal: the coupling rides every reading
		assertThat(answers(diagonalPosted(lvar(), lvar()), lvar())).containsExactly("_.0");
	}

	private static Posting diagonalPosted(Unifiable<String> l, Unifiable<String> r) {
		return Literal.relation(TableParkingPropagatorTest.class, "diagR")
				.arg("l", l)
				.arg("r", r)
				.solving(l.unifies(r)).posted();
	}

	@Test
	public void anEntailedRowDischargesTheAlternatives() {
		// one wide row at ONE entails every tuple: the disjunct absorbs the
		// whole ⊕ (1 ⊕ a = 1), so the constraint dissolves — alternatives
		// and all — instead of parking on the choice between two rows
		Unifiable<Integer> x = lvar();
		Unifiable<String> y = lvar();
		Posting tautology = Literal.relation(TableParkingPropagatorTest.class, "tautR")
				.arg("item", x)
				.arg("tag", y)
				.solving(Goal.success().or(x.unifies(1).and(y.unifies("b")))).posted();
		assertThat(answers(tautology, lvar())).containsExactly("_.0");
	}

	@Test
	public void aParkedTableGroundsRowWiseAtReify() {
		// a record still parked at reify grounds ROW-WISE through enforce —
		// the parking kind's leg of the sync discipline, never a cartesian
		Unifiable<Integer> x = lvar();
		assertThat(answers(posted(x, lvar()), x))
				.containsExactly("{1}", "{2}", "{3}");
	}

	@Test
	public void postedAgreesWithExists() {
		// the arc's oracle: a posted derived relation answers exactly like
		// its enumerating goal — enforce grounds what labelling left open
		Unifiable<Integer> px = lvar();
		Unifiable<String> py = lvar();
		Unifiable<Integer> ex = lvar();
		Unifiable<String> ey = lvar();
		assertThat(answers((Goal) posted(px, py), lval(Tuple.of(px, py))))
				.isEqualTo(answers(r(db, ex, ey), lval(Tuple.of(ex, ey))));
	}

	@Test
	public void aWideRowGroundsLeavingItsColumnOpenAtReify() {
		// two rows, one wide: enforce branches row-wise; the wide branch
		// binds the item, couples the tag to a fresh existential, and the
		// re-woken record discharges by entailment — the tag stays open
		Unifiable<Integer> x = lvar();
		Unifiable<String> t = lvar();
		Posting mixed = Literal.relation(TableParkingPropagatorTest.class, "mixedR")
				.arg("item", x)
				.arg("tag", t)
				.solving(x.unifies(7).or(x.unifies(8).and(t.unifies("a")))).posted();
		assertThat(answers(mixed, x)).containsExactlyInAnyOrder("{7}", "{8}");
	}

	@Test
	public void aConditionalRowRidesItsConditionThroughReify() {
		// the guarded row's branch imposes its nogood; the re-woken record
		// discharges as the lone survivor and the condition reifies as the
		// answer's residue
		Unifiable<Integer> i = lvar();
		Unifiable<String> y = lvar();
		Posting guarded = Literal.relation(TableParkingPropagatorTest.class, "condR")
				.arg("item", i)
				.arg("tag", y)
				.solving(i.unifies(7).and(exclude(y.unifies("q")))
						.or(i.unifies(8).and(y.unifies("a")))).posted();
		assertThat(answers(guarded, y))
				.containsExactlyInAnyOrder("_.0 : ¬(_.0 ≡ {q})", "{a}");
	}

	@Test
	public void aSyncSourceShippingConditionsImposesAtCommit() {
		// the kinds are symmetric: a hand-rolled SYNC source serving a
		// conditional answer is consumed like the parking kind's — imposed
		// at commit, never refused. The answer is manufactured by draining
		// a derived produce once and canning the emission
		Unifiable<Integer> gi = lvar();
		Unifiable<String> gt = lvar();
		GoalProducer producing = GoalProducer.of(rRel(),
				exclude(gi.unifies(2)), Arrays.asList(gi, gt), Table.empty());
		Call<Relation> wide = Call.of(rRel(), Answers.image(Any.of(0), Any.of(1)));
		List<Answer<Relation>> canned = new ArrayList<>();
		new BreadthFirstScheduler<>(producing.produce(wide, answer -> {
			canned.add(answer);
			return Fiber.done(Nothing.nothing());
		})).get();
		AnswerSource sync = probe -> canned;
		Unifiable<Integer> x = lvar();
		assertThat(answers(r(sync, x, lvar()).posted(), x))
				.containsExactly("_.0 : ¬(_.0 ≡ {2})");
		assertThat(answers(r(sync, lval(2), lval("q")).posted(), lvar())).isEmpty();
		assertThat(answers(r(sync, lval(1), lval("q")).posted(), lvar())).containsExactly("_.0");
	}

	@Test
	public void anAnyColumnProjectsToTopAndStoresNoSupport() {
		// two live entries, one leaving the tag free: the tag column projects
		// to TOP — no support may be stored, or values the Any-row admits
		// would be wrongly pruned. Asserted mid-solve; the deliberate failure
		// keeps reify (and enforcement, S3's stage) out of this receipt
		Unifiable<Integer> x = lvar();
		Unifiable<String> y = lvar();
		Posting mixed = Literal.relation(TableParkingPropagatorTest.class, "mixedTopR")
				.arg("item", x)
				.arg("tag", y)
				.solving(x.unifies(7).or(x.unifies(8).and(y.unifies("a")))).posted();
		assertThat(answers((Goal) mixed
				.and(probe(p -> {
					Theory<TableConstraints> live =
							Constraint.in(p, TableConstraints.class).get().getTheory();
					assertThat(TableConstraints.empty().getValue(live, p.walk(y)).isDefined())
							.describedAs("an Any column must not store a support")
							.isFalse();
				}))
				.and(Goal.failure()), x)).isEmpty();
	}
}
