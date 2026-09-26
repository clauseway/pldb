package org.clauseway.pldb.constraints;

// ABOUTME: pldb rows as a narrowing constraint store: a posted lookup is a named
// ABOUTME: propagator re-narrowing column supports through the index; branch at labelo.

import static org.clauseway.logic.unification.terms.LVal.lval;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.IntPredicate;
import java.util.stream.Collectors;
import org.clauseway.functional.fibers.Cont;
import org.clauseway.logic.constraints.Posting;
import org.clauseway.logic.constraints.Propagation;
import org.clauseway.logic.constraints.store.Constraint;
import org.clauseway.logic.constraints.store.Theory;
import org.clauseway.logic.goals.Goal;
import org.clauseway.logic.goals.Knowledge;
import org.clauseway.logic.goals.optimizer.Bounded;
import org.clauseway.logic.lattice.LatticeFactor;
import org.clauseway.logic.lattice.ParkingPropagator;
import org.clauseway.logic.lattice.Propagator;
import org.clauseway.logic.lattice.Update;
import org.clauseway.logic.unification.Prefix;
import org.clauseway.logic.unification.terms.LVar;
import org.clauseway.logic.unification.terms.Term;
import org.clauseway.logic.unification.terms.Unifiable;
import org.clauseway.pldb.AnswerProducer;
import org.clauseway.pldb.AnswerSource;
import org.clauseway.pldb.relations.Relation;
import org.clauseway.vavr.Tuple;
import org.clauseway.vavr.Tuple2;
import org.clauseway.vavr.collection.Array;
import org.clauseway.vavr.collection.IndexedSeq;

/**
 * The table constraint (docs/design/table-constraints.md): a posted lookup is
 * a DOMAIN over candidate rows, not an enumeration. The record is a named
 * value-equal {@link Propagator} watching the argument terms; on every wake it
 * re-queries the index under the current bindings, filters by the live column
 * supports (two posted tables sharing a variable prune each other — the GAC
 * join move), and answers the standard kernel verdicts: no candidates fails,
 * all-ground is a membership check, one candidate COLLAPSES to inferred
 * bindings, many candidates narrow each free column's {@link Support}.
 * Branching happens only at {@link #labelo} — or at reify, where
 * {@code enforce} grounds each surviving record row-wise.
 */
public final class TableConstraints extends LatticeFactor<Support, TableConstraints> {

	private static final TableConstraints EMPTY = new TableConstraints();

	private TableConstraints() {
	}

	public static TableConstraints empty() {
		return EMPTY;
	}

	/**
	 * Post a lookup as a constraint: park the table propagator and take its
	 * first examination — the initial narrowing — through the kernel's
	 * statement entry. The applied literal stays the enumerate-now alternative.
	 * Registration and doom ride the schema: a post whose bound pattern hits
	 * an empty bucket can never be satisfied (candidates only shrink), so
	 * the doom hoists the failure; a live post is one success, ever, and
	 * floats ahead of enumerations.
	 */
	public static Posting posted(AnswerSource source, Relation rel, List<Unifiable<?>> args) {
		return Propagation.activate(new TablePropagator(source, rel, Array.ofAll(args)));
	}

	/**
	 * Post a lookup over the ASYNC kind: the {@link TableParkingPropagator}
	 * parks, and its first examination arrives through the cascade wake —
	 * the parked kind's arrival semantics — not the statement entry.
	 * Conditional and Any-bearing answers are consumed natively.
	 */
	public static Posting posted(AnswerProducer producer, Relation rel, List<Unifiable<?>> args) {
		return Propagation.activate(new TableParkingPropagator(rel, producer, Array.ofAll(args)));
	}

	/**
	 * Post a RULE: the propagator composes it with the SOLVE's table at
	 * wake, so the extension it reads is the same entries every goal-side
	 * consumer shares — and an unstratified negation becomes a genuine
	 * cyclic wait the substrate can refuse, instead of a silent regress
	 * through fresh worlds.
	 */
	public static Posting postedRule(Relation rel, Goal rule, List<Unifiable<?>> args) {
		return Propagation.activate(TableParkingPropagator.rule(rel, rule, Array.ofAll(args)));
	}

	/**
	 * The declared branch point: enumerate each variable's LIVE support, in
	 * the given order — collapses cascade between labellings, so later
	 * variables usually bind without branching. Each labelling is priced at
	 * its live support size, so the optimizer's cheapest-first sort is CP's
	 * min-domain heuristic.
	 */
	public static Goal labelo(Unifiable<?>... xs) {
		return Arrays.stream(xs)
				.map(x -> Bounded.sighted(p -> labelOrder(p, x), label(x)))
				.reduce(Goal::and)
				.orElseGet(Goal::success);
	}

	private static long labelOrder(Knowledge p, Term<?> x) {
		Term<?> w = p.walk(x);
		if (!w.asVar().isPresent()) {
			return 1;
		}
		return Constraint.in(p, TableConstraints.class)
				.flatMap(pair -> EMPTY.getValue(pair.getTheory(), w))
				.map(support -> (long) support.getValues().size())
				.getOrElse(1L);
	}

	/**
	 * Answers may not leave with live records: each surviving record grounds
	 * ROW-WISE — the store recognizes its own bodies and branches over each
	 * record's live candidates, FEWEST CANDIDATES FIRST (fail-first: each
	 * grounding collapses the rest, so the narrowest record minimizes total
	 * branching). Self-sufficient for lone and joined records alike.
	 */
	@Override
	public <T> Goal enforce(Term<T> x) {
		return groundRecords();
	}

	/** Pick the narrowest live record, enumerate it, repeat against the new state. */
	private static Goal groundRecords() {
		return groundNarrowestFirst(TableConstraints::liveRecords);
	}

	/** Each live record priced at its bucket, its row enumerator as the goal. */
	private static List<Tuple2<Long, Goal>> liveRecords(Knowledge s) {
		Theory<TableConstraints> live = Constraint.in(s, TableConstraints.class)
				.map(Constraint::getTheory)
				.getOrNull();
		if (live == null) {
			return Collections.emptyList();
		}
		List<Tuple2<Long, Goal>> survivors = new ArrayList<>();
		for (Propagator<TableConstraints> p : EMPTY.props(live).collect(Collectors.toList())) {
			if (!(p instanceof TablePropagator)) {
				continue;
			}
			Array<Term<?>> walked = p.watchedTerms().map(t -> (Term<?>) s.walk(t));
			if (walked.forAll(w -> w.isVal())) {
				continue;
			}
			survivors.add(Tuple.of(((TablePropagator) p).estimate(walked),
					((TablePropagator) p).enumerate(p.watchedTerms())));
		}
		for (ParkingPropagator<TableConstraints> p : EMPTY.parkingProps(live).collect(Collectors.toList())) {
			if (!(p instanceof TableParkingPropagator)) {
				continue;
			}
			Array<Term<?>> walked = p.watchedTerms().map(t -> (Term<?>) s.walk(t));
			if (walked.forAll(w -> w.isVal())) {
				continue;
			}
			survivors.add(Tuple.of(((TableParkingPropagator) p).estimate(walked, s),
					((TableParkingPropagator) p).enumerate(p.watchedTerms())));
		}
		return survivors;
	}

	private static Goal label(Term<?> x) {
		return s -> {
			Term<?> w = s.walk(x);
			if (!w.asVar().isPresent()) {
				return Cont.just(s);
			}
			return Constraint.in(s, TableConstraints.class)
					.flatMap(pair -> EMPTY.getValue(pair.getTheory(), w))
					.map(support -> support.getValues().toJavaStream()
							.map(v -> unifyWith(w, v))
							.reduce(Goal::or)
							.orElseGet(Goal::failure)
							.apply(s))
					.getOrElse(() -> Cont.just(s));
		};
	}

	@SuppressWarnings("unchecked")
	private static Goal unifyWith(Term<?> w, Object value) {
		return ((LVar<Object>) w.asVar().get()).unifies(value);
	}

	/** One candidate left: bind every free column — the FD-collapse move on tuples. */
	static Update collapse(Knowledge state, Theory<TableConstraints> theory, Array<Term<?>> walked, Array<Object> row) {
		Update.Applied result = Update.applied(theory);
		boolean bound = false;
		for (int i = 0; i < walked.size(); i++) {
			Term<?> w = walked.get(i);
			if (w.isVal()) {
				continue;
			}
			Prefix prefix = bindingOf(state, w, row.get(i));
			if (prefix != null) {
				result = result.withInferred(prefix);
				bound = true;
			}
		}
		return bound ? result : Update.unchanged();
	}

	private static Prefix bindingOf(Knowledge state, Term<?> w, Object value) {
		return Prefix.binding(state.substitution(), w.asVar().get(), lval(value))
				.getOrNull();
	}

	/**
	 * Is the column a JOIN column right now? Someone already stored a support
	 * for it, or a second propagator watches it — sharedness is checked per
	 * wake, not per post, because it arrives late (a later posting, or an
	 * alias welding two columns).
	 */
	private static boolean shared(Theory<TableConstraints> theory, Knowledge state, Term<?> w) {
		if (EMPTY.getValue(theory, w).isDefined()) {
			return true;
		}
		int watchers = 0;
		for (Propagator<TableConstraints> p : EMPTY.props(theory).collect(Collectors.toList())) {
			if (p.watches(state, w) && ++watchers >= 2) {
				return true;
			}
		}
		return false;
	}

	/**
	 * The pattern-aware narrowing: a candidate cell may be FREE (an Any in a
	 * row admits every value there), and a column ANY candidate leaves free
	 * projects to TOP — which the store spells as absence, so the column is
	 * SKIPPED rather than stored. Monotone: the live candidate set only
	 * shrinks, so a skipped column can only gain a support later, never owe
	 * a retraction.
	 */
	static Update narrowPatterns(Knowledge state, Theory<TableConstraints> theory,
			Array<Term<?>> walked, List<IndexedSeq<Term<Object>>> candidates) {
		return narrow(state, theory, walked,
				candidates.stream()
						.map(c -> Array.ofAll(c.map(cell ->
								cell.isVal() ? cell.get() : FREE_CELL)))
						.collect(Collectors.toList()),
				column -> candidates.stream().anyMatch(c -> !c.get(column).isVal()));
	}

	/** A free candidate cell's placeholder — never stored, columns holding one are skipped. */
	private static final Object FREE_CELL = new Object();

	/**
	 * Narrow each free column against its projection over the candidates.
	 * Projections are TRANSIENT: a singleton binds its column right here
	 * (every candidate agrees, and some candidate must hold), and a wider
	 * projection is STORED only when the column is shared — an unshared
	 * support has no reader, so the shadow's cost is the join width, not
	 * every posted column.
	 */
	static Update narrow(Knowledge state, Theory<TableConstraints> theory,
			Array<Term<?>> walked, List<Array<Object>> candidates) {
		return narrow(state, theory, walked, candidates, column -> false);
	}

	@SuppressWarnings("unchecked")
	private static Update narrow(Knowledge state, Theory<TableConstraints> theory,
			Array<Term<?>> walked, List<Array<Object>> candidates,
			IntPredicate topColumn) {
		Theory<TableConstraints> current = theory;
		List<Prefix> inferred = new ArrayList<>();
		List<Term<?>> reexamine = new ArrayList<>();
		for (int i = 0; i < walked.size(); i++) {
			Term<?> w = walked.get(i);
			if (w.isVal() || topColumn.test(i)) {
				continue;
			}
			int column = i;
			Support projection = Support.ofAll(candidates.stream()
					.map(f -> f.get(column))
					.collect(Collectors.toSet()));
			if (projection.asPoint().isDefined()) {
				Prefix prefix = bindingOf(state, w, projection.asPoint().get());
				if (prefix != null) {
					inferred.add(prefix);
				}
				continue;
			}
			if (!shared(current, state, w)) {
				continue;
			}
			Update step = EMPTY.update(current, state, w, projection);
			Theory<TableConstraints> before = current;
			current = step.match(
					() -> null,
					() -> before,
					applied -> {
						inferred.addAll(applied.inferred());
						reexamine.addAll(applied.reexamine());
						return (Theory<TableConstraints>) applied.theory();
					});
			if (current == null) {
				return Update.fail();
			}
		}
		if (current == theory && inferred.isEmpty() && reexamine.isEmpty()) {
			return Update.unchanged();
		}
		Update.Applied result = Update.applied(current);
		for (Prefix prefix : inferred) {
			result = result.withInferred(prefix);
		}
		for (Term<?> x : reexamine) {
			result = result.withReexamine(x);
		}
		return result;
	}

}
