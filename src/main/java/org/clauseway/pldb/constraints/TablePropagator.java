package org.clauseway.pldb.constraints;

// ABOUTME: A posted table as a propagator schema: re-narrowing through the index
// ABOUTME: on wake, and the row enumerator enforce uses to ground survivors.

import static org.clauseway.logic.unification.terms.LVal.lval;

import java.util.List;
import org.clauseway.functional.fibers.Cont;
import org.clauseway.functional.tuples.Tuple;
import org.clauseway.logic.constraints.Propagation;
import org.clauseway.logic.constraints.store.Constraint;
import org.clauseway.logic.constraints.store.Theory;
import org.clauseway.logic.goals.Conjunction;
import org.clauseway.logic.goals.Goal;
import org.clauseway.logic.goals.Knowledge;
import org.clauseway.logic.lattice.Propagator;
import org.clauseway.logic.lattice.Verdict;
import org.clauseway.logic.solving.Call;
import org.clauseway.logic.unification.MiniKanren;
import org.clauseway.logic.unification.Substitutions;
import org.clauseway.logic.unification.terms.Reified;
import org.clauseway.logic.unification.terms.Term;
import org.clauseway.pldb.AnswerSource;
import org.clauseway.pldb.relations.Relation;
import org.clauseway.vavr.collection.Array;

/**
 * The record's re-examination, POSITIONAL over the watched terms: walk, probe
 * the source by the call key under the current bindings, filter by the live
 * supports of whatever is free, verdict. Also the record's ROW ENUMERATOR: the
 * owning store recognizes this schema on its own propagators and grounds a
 * surviving record at reify by branching over its live candidate rows —
 * {@code posted} is self-sufficient whether or not anything joins it. The name
 * carries the relation and its source, so two posts of one lookup on the same
 * terms are the same knowledge stated twice.
 */
final class TablePropagator extends Propagator<TableConstraints> {

	private final AnswerSource source;
	private final Relation rel;

	TablePropagator(AnswerSource source, Relation rel, Array<? extends Term<?>> args) {
		super(args);
		this.source = source;
		this.rel = rel;
	}

	@Override
	public Verdict propagate(Knowledge pkg) {
		Array<Term<?>> walked = watchedTerms().map(t -> (Term<?>) pkg.walk(t));
		List<Extension.Row> live = Extension.live(walked,
				Extension.fold(source.answers(Extension.probe(pkg, rel, walked).ground())), theory(pkg));
		return Extension.verdict(walked, live, theory -> theory.without(this));
	}

	private static Theory<TableConstraints> theory(Knowledge pkg) {
		return Constraint.in(pkg, TableConstraints.class).get().getTheory();
	}

	@Override
	public TablePropagator watching(Array<? extends Term<?>> terms) {
		return new TablePropagator(source, rel, terms);
	}

	@Override
	public TableConstraints empty() {
		return TableConstraints.empty();
	}

	@Override
	public String name() {
		return rel.getName() + "@" + source.id();
	}

	@Override
	public Class<? extends TableConstraints> getFactorClass() {
		return TableConstraints.class;
	}

	/**
	 * A post whose bound pattern hits an empty bucket can never be satisfied —
	 * candidates only shrink, so the failure hoists. Monotone under binding
	 * growth: bindings only sharpen the probe.
	 */
	@Override
	public boolean doomed(Knowledge p) {
		return estimate(watchedTerms().map(t -> (Term<?>) p.substitution().walk(t))) == 0;
	}

	/**
	 * Branch over the record's LIVE disjuncts — each branch restates its row
	 * whole and RE-WAKES the record so the verdict that follows discharges
	 * it. Exactly the rows, never a cartesian product of columns. All-ground
	 * is a no-op: the record verifies itself through the ordinary wake.
	 */
	Goal enumerate(Array<? extends Term<?>> watched) {
		return s -> {
			Array<Term<?>> walked = watched.map(t -> (Term<?>) s.walk(t));
			if (walked.forAll(w -> w.isVal())) {
				return Cont.just(s);
			}
			List<Extension.Row> live = Extension.live(walked,
					Extension.fold(source.answers(Extension.probe(s, rel, walked).ground())), theory(s));
			return Extension.branchRestates(live, walked)
					.map(branch -> (Goal) Conjunction.of(branch, Propagation.activate(this)))
					.reduce(Goal::or)
					.orElseGet(Goal::failure)
					.apply(s);
		};
	}

	/**
	 * The record's rank for fail-first ordering: the index bucket size under
	 * the current bindings. An upper bound (support filtering not applied) —
	 * a heuristic owes a rank, not exactness, and it costs a bucket lookup
	 * instead of a materialization. Pricing carries no region: the upper
	 * bound stays sound ignoring it.
	 */
	long estimate(Array<Term<?>> walked) {
		Reified<?> image = MiniKanren.reify(Substitutions.empty(),
						lval(Tuple.ofAll(walked.map(Term::getObjectTerm).toJavaArray())).getObjectTerm())
				.ground();
		return source.estimate(Call.of(rel, image));
	}

}
