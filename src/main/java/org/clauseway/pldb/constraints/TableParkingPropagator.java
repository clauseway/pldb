package org.clauseway.pldb.constraints;

// ABOUTME: The parking posted table: a Condition-valued constraint over an AnswerProducer —
// ABOUTME: conditional rows impose at commit, Any rows admit everything, verdicts never test.

import static org.clauseway.logic.unification.terms.LVal.lval;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Function;
import org.clauseway.functional.Nothing;
import org.clauseway.functional.fibers.Fiber;
import org.clauseway.functional.fibers.interpreter.Scope;
import org.clauseway.functional.tuples.Tuple;
import org.clauseway.logic.constraints.Propagation;
import org.clauseway.logic.constraints.store.Constraint;
import org.clauseway.logic.constraints.store.Theory;
import org.clauseway.logic.goals.Conjunction;
import org.clauseway.logic.goals.Goal;
import org.clauseway.logic.goals.Knowledge;
import org.clauseway.logic.lattice.ParkingPropagator;
import org.clauseway.logic.lattice.Verdict;
import org.clauseway.logic.solving.JoinMap;
import org.clauseway.logic.solving.Condition;
import org.clauseway.logic.solving.Call;
import org.clauseway.logic.tabling.table.Table;
import org.clauseway.logic.unification.MiniKanren;
import org.clauseway.logic.unification.Substitutions;
import org.clauseway.logic.unification.terms.Reified;
import org.clauseway.logic.unification.terms.Term;
import org.clauseway.logic.unification.terms.Unifiable;
import org.clauseway.pldb.AnswerProducer;
import org.clauseway.pldb.GoalProducer;
import org.clauseway.logic.solving.Answer;
import org.clauseway.pldb.relations.Relation;
import org.clauseway.vavr.collection.Array;

/**
 * The posted table over the ASYNC kind, read as its algebra: the constraint
 * "t ∈ R" over a conditional extension {(rᵢ, Cᵢ)} IS the Condition
 * ⊕ᵢ (t=rᵢ ⊗ Cᵢ), and every wake is a monotone simplification of that
 * value. Rows are patterns — a FREE cell (an Any) admits every value — and
 * conditions are never tested, only IMPOSED at commit: a committing verdict
 * restates the entry whole (image ⊗ condition) through the run lane, so a
 * condition cannot be dropped because it is not separable. Verdicts:
 * <ul>
 * <li>no live entries — fail;</li>
 * <li>some live row ENTAILED (condition {@code ONE}, binding half imposing
 * nothing) — its disjunct is 1 and 1 ⊕ a = 1: subsumed, the alternatives
 * dissolve with the constraint;</li>
 * <li>all watched terms ground — the residual is the ⊕ of the subsuming
 * entries' conditions, discharged as a conde of per-conjunct restates;</li>
 * <li>one live entry — a ground row at {@code ONE} collapses to inferred
 * bindings (the sync kind's move); any other entry discharges by
 * restate;</li>
 * <li>otherwise — narrow the shared free columns, a column any live row
 * leaves free projecting to TOP (absence in the store).</li>
 * </ul>
 *
 * <p>The extension is drained from produce TO THE SEAL before any verdict —
 * the seal is the soundness gate (the extension grows while filtering
 * shrinks) — and folded per row by ⊕ in a {@link JoinMap}, so duplicate
 * derivations of one row factor into a single entry by distributivity.
 */
public class TableParkingPropagator extends ParkingPropagator<TableConstraints> {
	private final Relation rel;
	private final String label;
	private final Function<Knowledge, AnswerProducer> producer;

	protected TableParkingPropagator(Relation rel, AnswerProducer producer, Array<? extends Term<?>> watchedTerms) {
		this(rel, producer.id(), pkg -> producer, watchedTerms);
	}

	private TableParkingPropagator(Relation rel, String label,
			Function<Knowledge, AnswerProducer> producer, Array<? extends Term<?>> watchedTerms) {
		super(watchedTerms);
		this.rel = rel;
		this.label = label;
		this.producer = producer;
	}

	/**
	 * The rule kind: the producer is COMPOSED at wake — the rule with the
	 * SOLVE's table, extracted from whichever package the examination
	 * arrives in. One field, one question: given a package, what do I drain?
	 */
	static TableParkingPropagator rule(Relation rel, Goal rule, Array<Unifiable<?>> heads) {
		return new TableParkingPropagator(rel, "rule",
				pkg -> GoalProducer.of(rel, rule, heads.toJavaList(), tableOf(pkg, rel)), heads);
	}

	private static Table tableOf(Knowledge pkg, Relation rel) {
		return pkg.getStores().get(Table.class)
				.map(Table.class::cast)
				.getOrElseThrow(() -> new IllegalStateException(
						"posted rule '" + rel.getName() + "' outside a solve: no table in the package"));
	}

	@Override
	public Fiber<Verdict> propagate(Knowledge pkg) {
		Array<Term<?>> walked = watchedTerms().map(t -> (Term<?>) pkg.walk(t));
		return Extension.probe(pkg, rel, walked)
				.flatMap(probe -> extension(probe, pkg))
				.map(extension -> Extension.verdict(walked,
						Extension.live(walked, extension, theory(pkg)),
						theory -> theory.without(this)));
	}

	private static Theory<TableConstraints> theory(Knowledge pkg) {
		return Constraint.in(pkg, TableConstraints.class).get().getTheory();
	}

	/**
	 * Produce drained TO THE SEAL, folded per row by ⊕ — the extension view.
	 * The claimed sub-scope catches forked deliveries: produce's own fiber
	 * can complete while flat-forked deliveries still run, so the seal of
	 * the claiming scope, not produce's return, is what makes the drain
	 * complete.
	 */
	private Fiber<JoinMap<Reified<?>, Condition>> extension(Call<Relation> probe, Knowledge pkg) {
		Scope sub = Scope.scope("TableParkingPropagatorProduction");
		Queue<Answer<Relation>> delivered = new ConcurrentLinkedQueue<>();
		return Fiber.claim(sub, producer.apply(pkg).produce(probe, answer -> {
					delivered.add(answer);
					return Fiber.done(Nothing.nothing());
				}))
				.flatMap(explored -> Fiber.sealed(sub))
				.map(sealed -> Extension.fold(delivered));
	}

	/**
	 * The record's reify-time grounding: branch over the live disjuncts —
	 * each branch restates its row whole, then RE-WAKES the record so the
	 * verdict that follows discharges it (all-ground, entailed, or the lone
	 * survivor's commit). The parking leg of enforce's row-wise discipline;
	 * the re-wake matters because a branch whose row binds nothing (a wide
	 * or conditional row) would otherwise leave the record parked at reify.
	 */
	Goal enumerate(Array<? extends Term<?>> watched) {
		return st -> k -> {
			Array<Term<?>> walked = watched.map(t -> (Term<?>) st.walk(t));
			if (walked.forAll(w -> w.isVal())) {
				return Goal.success().apply(st).apply(k);
			}
			return Extension.probe(st, rel, walked)
					.flatMap(probe -> extension(probe, st))
					.flatMap(extension ->
							Extension.branchRestates(Extension.live(walked, extension, theory(st)), walked)
									.map(branch -> (Goal) Conjunction.of(branch, Propagation.activate(this)))
									.reduce(Goal::or)
									.orElseGet(Goal::failure)
									.apply(st).apply(k));
		};
	}

	/**
	 * The record's rank under the current bindings: the producer's sealed
	 * knowledge where it prices, the optimizer barrier otherwise. Pricing
	 * carries no region: the upper bound stays sound ignoring it.
	 */
	long estimate(Array<Term<?>> walked, Knowledge pkg) {
		return producer.apply(pkg).estimate(Call.of(rel, MiniKanren.reify(Substitutions.empty(),
				lval(Tuple.ofAll(walked.map(Term::getObjectTerm).toJavaArray())).getObjectTerm()).ground()));
	}

	/**
	 * A bound pattern over an empty sealed bucket can never be satisfied —
	 * candidates only shrink. An unpriced producer answers the barrier and
	 * is never doomed here.
	 */
	@Override
	public boolean doomed(Knowledge p) {
		return estimate(watchedTerms().map(t -> (Term<?>) p.substitution().walk(t)), p) == 0;
	}

	@Override
	public ParkingPropagator<TableConstraints> watching(Array<? extends Term<?>> terms) {
		return new TableParkingPropagator(rel, label, producer, terms);
	}

	@Override
	public Class<? extends TableConstraints> getFactorClass() {
		return TableConstraints.class;
	}

	@Override
	public String name() {
		return rel.getName() + "@" + label;
	}

	@Override
	public TableConstraints empty() {
		return TableConstraints.empty();
	}
}
