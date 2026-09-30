package org.clauseway.pldb;

// ABOUTME: The table constraint (docs/design/table-constraints.md): posted lookups
// ABOUTME: narrow as domains — joins prune, singletons collapse, branch only at labelling.

import org.clauseway.logic.solving.Query;
import static org.assertj.core.api.Assertions.assertThat;
import static org.clauseway.logic.unification.terms.LVal.lval;
import static org.clauseway.logic.unification.terms.LVar.lvar;

import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.clauseway.functional.fibers.Cont;
import org.clauseway.functional.tuples.Tuple;
import org.clauseway.logic.constraints.store.Constraint;
import org.clauseway.logic.constraints.store.Theory;
import org.clauseway.logic.goals.Goal;
import org.clauseway.logic.goals.Knowledge;
import org.clauseway.logic.goals.optimizer.Bounded;
import org.clauseway.logic.solving.Call;
import org.clauseway.logic.unification.terms.Term;
import org.clauseway.logic.unification.terms.Unifiable;
import org.clauseway.pldb.constraints.Support;
import org.clauseway.pldb.constraints.TableConstraints;
import org.clauseway.pldb.inmemory.AnswerStore;
import org.clauseway.logic.solving.Answer;
import org.clauseway.pldb.relations.Literal;
import org.clauseway.pldb.relations.Property;
import org.clauseway.pldb.relations.Relation;
import org.junit.Test;

/**
 * A posted lookup is a DOMAIN over candidate rows, not an enumeration: bindings
 * re-narrow it through the index, two posted tables sharing a variable prune
 * each other through their column supports, a singleton candidate set collapses
 * to bindings without branching, and branching happens only at {@code labelo}
 * or at reify (enforce grounds each surviving record row-wise).
 */
public class TableConstraintsTest {

	private static Literal r(AnswerSource db, Unifiable<Integer> item, Unifiable<String> tag) {
		return Literal.relation(TableConstraintsTest.class, "r")
				.arg("item", item).indexed()
				.arg("tag", tag).indexed()
				.from(db);
	}

	private static Relation rRel() {
		return r(null, lvar(), lvar()).getRel();
	}

	private static Literal s(AnswerSource db, Unifiable<String> label, Unifiable<Integer> price) {
		return Literal.relation(TableConstraintsTest.class, "s")
				.arg("label", label).indexed()
				.arg("price", price).indexed()
				.from(db);
	}

	private static Relation sRel() {
		return s(null, lvar(), lvar()).getRel();
	}

	private static Literal t(AnswerSource db, Unifiable<Integer> item, Unifiable<String> tag) {
		return Literal.relation(TableConstraintsTest.class, "t")
				.arg("item", item).indexed()
				.arg("tag", tag).indexed()
				.from(db);
	}

	private static Relation tRel() {
		return t(null, lvar(), lvar()).getRel();
	}

	private static final Property<Integer> item = Property.of("item");
	private static final Property<String> tag = Property.of("tag");
	private static final Property<String> label = Property.of("label");
	private static final Property<Integer> price = Property.of("price");

	private static final AnswerStore db = AnswerStore.empty()
			.asserting(Arrays.asList(
					r(null, lval(1), lval("a")),
					r(null, lval(2), lval("b")),
					r(null, lval(3), lval("c")),
					s(null, lval("a"), lval(10)),
					s(null, lval("b"), lval(20)),
					s(null, lval("d"), lval(40)),
					t(null, lval(7), lval("u")),
					t(null, lval(7), lval("v"))));

	/** A goal that runs assertions against the live package and succeeds. */
	private static Goal probe(Consumer<Knowledge> check) {
		return p -> {
			check.accept(p);
			return Cont.just(p);
		};
	}

	@Test
	public void aRecordPricedAtTheBarrierStillGrounds() {
		// pricing is an ORDER, not a gate: a source that can only answer the
		// optimizer barrier (no statistics — a remote backend's honest
		// estimate) must still have its surviving records enumerated at
		// enforce; the narrowest-selection must tolerate every candidate
		// pricing at Long.MAX_VALUE
		AnswerSource barrier = new AnswerSource() {
			@Override
			public Iterable<Answer<Relation>> answers(Call<Relation> probe) {
				return db.answers(probe);
			}

			@Override
			public long estimate(Call<Relation> probe) {
				return Long.MAX_VALUE;
			}
		};
		Unifiable<String> viaBarrier = lvar();
		Unifiable<String> viaDb = lvar();
		List<String> barrierAnswers = Query.of(r(barrier, lvar(), viaBarrier).posted()).solve(viaBarrier)
				.map(Object::toString)
				.sorted()
				.collect(Collectors.toList());
		List<String> dbAnswers = Query.of(r(db, lvar(), viaDb).posted()).solve(viaDb)
				.map(Object::toString)
				.sorted()
				.collect(Collectors.toList());
		assertThat(dbAnswers).isNotEmpty();
		assertThat(barrierAnswers).isEqualTo(dbAnswers);
	}

	@Test
	public void aJoinPrunesThroughSharedColumnSupportsWithoutBranching() {
		Unifiable<Integer> x = lvar();
		Unifiable<String> y = lvar();
		Unifiable<Integer> z = lvar();

		List<String> answers = Query.of(r(db, x, y).posted()
				.and(s(db, y, z).posted())
				.and(probe(p -> {
					Theory<TableConstraints> store = Constraint.in(p, TableConstraints.class).get().getTheory();
					// y is the SHARED column: r's tags met with s's labels,
					// {a,b,c} ∧ {a,b,d} = {a,b} — the only support materialized
					assertThat(TableConstraints.empty().getValue(store, p.walk(y)).get())
							.isEqualTo(Support.of("a", "b"));
					// x and z are lone columns: their projections are transient,
					// nobody reads them, nothing is stored
					assertThat(TableConstraints.empty().getValue(store, p.walk(x)).isDefined()).isFalse();
					assertThat(TableConstraints.empty().getValue(store, p.walk(z)).isDefined()).isFalse();
				}))).solve(lval(Tuple.of(x, y, z)))
				.map(Term::get)
				.map(t -> t._1.get() + "," + t._2.get() + "," + t._3.get())
				.collect(Collectors.toList());

		assertThat(answers).containsExactlyInAnyOrder("1,a,10", "2,b,20");
	}

	@Test
	public void aSingletonCandidateSetCollapsesToBindingsWithoutBranching() {
		Unifiable<String> y = lvar();

		long count = Query.of(r(db, lval(2), y).posted()
				.and(probe(p -> assertThat(p.walk(y).get()).isEqualTo("b")))).solve(y)
				.count();
		assertThat(count).isEqualTo(1);
	}

	@Test
	public void bindingAfterPostingWakesTheTableAndCollapses() {
		Unifiable<Integer> x = lvar();
		Unifiable<String> y = lvar();

		long count = Query.of(r(db, x, y).posted()
				.and(x.unifies(3))
				.and(probe(p -> assertThat(p.walk(y).get()).isEqualTo("c")))).solve(y)
				.count();
		assertThat(count).isEqualTo(1);
	}

	@Test
	public void anEmptyCandidateSetFails() {
		Unifiable<String> y = lvar();
		assertThat(Query.of(r(db, lval(99), y).posted()).solve(y).count()).isZero();
	}

	@Test
	public void aGroundPostIsAMembershipCheck() {
		Unifiable<String> out = lvar();
		assertThat(Query.of(r(db, lval(1), lval("a")).posted().and(out.unifies("yes"))).solve(out).count())
				.isEqualTo(1);
		assertThat(Query.of(r(db, lval(1), lval("b")).posted().and(out.unifies("yes"))).solve(out).count())
				.isZero();
	}

	@Test
	public void labeloBranchesOverTheLiveSupport() {
		Unifiable<Integer> x = lvar();
		Unifiable<String> y = lvar();

		// labelling the SHARED column: each y branch collapses both records
		List<Integer> items = Query.of(r(db, x, y).posted()
				.and(s(db, y, lvar()).posted())
				.and(TableConstraints.labelo(y))).solve(x)
				.map(Term::get)
				.collect(Collectors.toList());
		assertThat(items).containsExactlyInAnyOrder(1, 2);
	}

	@Test
	public void aLoneTableStoresNoSupports() {
		Unifiable<Integer> x = lvar();
		Unifiable<String> y = lvar();

		long count = Query.of(r(db, x, y).posted()
				.and(probe(p -> {
					Theory<TableConstraints> store = Constraint.in(p, TableConstraints.class).get().getTheory();
					assertThat(TableConstraints.empty().getValue(store, p.walk(x)).isDefined()).isFalse();
					assertThat(TableConstraints.empty().getValue(store, p.walk(y)).isDefined()).isFalse();
				}))
				.and(x.unifies(1))
				.and(y.unifies("a"))).solve(y)
				.count();
		assertThat(count).isEqualTo(1);
	}

	@Test
	public void aLoneRecordGroundsRowWiseAtReify() {
		// posted is self-sufficient whether or not anything joins it: at
		// reify each surviving record enumerates its live candidate ROWS —
		// exactly the rows, never a cartesian product of columns
		Unifiable<Integer> x = lvar();
		Unifiable<String> y = lvar();

		List<String> rows = Query.of(r(db, x, y).posted()).solve(lval(Tuple.of(x, y)))
				.map(Term::get)
				.map(p -> p._1.get() + "," + p._2.get())
				.collect(Collectors.toList());
		assertThat(rows).containsExactlyInAnyOrder("1,a", "2,b", "3,c");
	}

	@Test
	public void aLoneColumnCollapseStillBindsTransiently() {
		// both rows of t agree on item — the singleton projection binds x
		// without ever storing a support
		Unifiable<Integer> x = lvar();
		Unifiable<String> y = lvar();

		long count = Query.of(t(db, x, y).posted()
				.and(probe(p -> {
					assertThat(p.walk(x).get()).isEqualTo(7);
					Theory<TableConstraints> store = Constraint.in(p, TableConstraints.class).get().getTheory();
					assertThat(TableConstraints.empty().getValue(store, p.walk(y)).isDefined()).isFalse();
				}))
				.and(y.unifies("u"))).solve(y)
				.count();
		assertThat(count).isEqualTo(1);
	}

	@Test
	public void aPostedGoalIsDoomedWhenDeadAndAlwaysPricesOne() {
		Unifiable<String> y = lvar();
		// bound arg with an empty bucket: no candidate can ever appear —
		// doom says so, and the price does not flinch (the kill is the
		// pruning pass's, never the sort key's)
		assertThat(r(db, lval(99), y).posted().doomed(Knowledge.empty())).isTrue();
		assertThat(((Bounded) r(db, lval(99), y).posted()).answers(Knowledge.empty()))
				.isEqualTo(1);
		// a live post is a constraint statement: one success, ever
		assertThat(r(db, lval(1), y).posted().doomed(Knowledge.empty())).isFalse();
		assertThat(((Bounded) r(db, lval(1), y).posted()).answers(Knowledge.empty()))
				.isEqualTo(1);
		Unifiable<Integer> x = lvar();
		assertThat(((Bounded) r(db, x, y).posted()).answers(Knowledge.empty()))
				.isEqualTo(1);
	}

	@Test
	public void labeloPricesAtTheLiveSupportSize() {
		Unifiable<Integer> x = lvar();
		Unifiable<String> y = lvar();
		Unifiable<Integer> z = lvar();
		Knowledge[] captured = new Knowledge[1];

		long answers = Query.of(r(db, x, y).posted()
				.and(s(db, y, z).posted())
				.and(probe(p -> captured[0] = p))).solve(lval(Tuple.of(x, y, z)))
				.count();
		assertThat(answers).isEqualTo(2);

		// y's live support after the join narrowing is {a,b}
		assertThat(((Bounded) TableConstraints.labelo(y)).answers(captured[0]))
				.isEqualTo(2);
		// an unsupported or bound variable labels as a pass-through
		assertThat(((Bounded) TableConstraints.labelo(x)).answers(captured[0]))
				.isEqualTo(1);
	}

	@Test
	public void enforceGroundsIndependentRecordsCompletely() {
		// two unrelated lone records: enforce grounds both, fewest rows first,
		// yielding the full cartesian of their candidate rows
		Unifiable<Integer> x1 = lvar();
		Unifiable<String> y1 = lvar();
		Unifiable<Integer> x2 = lvar();
		Unifiable<String> y2 = lvar();

		long count = Query.of(r(db, x1, y1).posted()
				.and(t(db, x2, y2).posted())).solve(lval(Tuple.of(x1, y1, x2, y2)))
				.count();
		assertThat(count).isEqualTo(6);
	}

	@Test
	public void lateAliasingMaterializesTheSharedColumn() {
		Unifiable<Integer> x = lvar();
		Unifiable<String> y = lvar();
		Unifiable<String> l = lvar();
		Unifiable<Integer> z = lvar();

		// posted apart: no sharing, nothing stored; the alias welds y~l and
		// both records materialize the column and meet
		long count = Query.of(r(db, x, y).posted()
				.and(s(db, l, z).posted())
				.and(probe(p -> {
					Theory<TableConstraints> store = Constraint.in(p, TableConstraints.class).get().getTheory();
					assertThat(TableConstraints.empty().getValue(store, p.walk(y)).isDefined()).isFalse();
					assertThat(TableConstraints.empty().getValue(store, p.walk(l)).isDefined()).isFalse();
				}))
				.and(y.unifies(l))
				.and(probe(p -> {
					Theory<TableConstraints> store = Constraint.in(p, TableConstraints.class).get().getTheory();
					assertThat(TableConstraints.empty().getValue(store, p.walk(y)).get())
							.isEqualTo(Support.of("a", "b"));
				}))).solve(lval(Tuple.of(x, y, z)))
				.count();
		assertThat(count).isEqualTo(2);
	}

	@Test
	public void postedAgreesWithExists() {
		Unifiable<Integer> x1 = lvar();
		Unifiable<String> y1 = lvar();
		Unifiable<Integer> z1 = lvar();
		List<String> viaExists = Query.of(r(db, x1, y1)
				.and(s(db, y1, z1))).solve(lval(Tuple.of(x1, y1, z1)))
				.map(Object::toString)
				.sorted()
				.collect(Collectors.toList());

		Unifiable<Integer> x2 = lvar();
		Unifiable<String> y2 = lvar();
		Unifiable<Integer> z2 = lvar();
		List<String> viaPosted = Query.of(r(db, x2, y2).posted()
				.and(s(db, y2, z2).posted())).solve(lval(Tuple.of(x2, y2, z2)))
				.map(Object::toString)
				.sorted()
				.collect(Collectors.toList());

		assertThat(viaPosted).hasSize(2).isEqualTo(viaExists);
	}
}
