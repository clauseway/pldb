package org.clauseway.pldb.constraints;

// ABOUTME: The posted table's extension read as its algebra: rows ⊕-folded with their
// ABOUTME: conditions, filtered live, and answered with the shared verdict ladder.

import static org.clauseway.logic.unification.terms.LVal.lval;

import java.util.List;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import lombok.Value;
import org.clauseway.functional.Exceptions;
import org.clauseway.functional.fibers.Fiber;
import org.clauseway.functional.tuples.Tuple;
import org.clauseway.logic.constraints.store.Theory;
import org.clauseway.logic.goals.Goal;
import org.clauseway.logic.goals.Knowledge;
import org.clauseway.logic.lattice.Update;
import org.clauseway.logic.lattice.Verdict;
import org.clauseway.logic.tabling.JoinMap;
import org.clauseway.logic.tabling.conditions.Condition;
import org.clauseway.logic.tabling.conditions.Residues;
import org.clauseway.logic.tabling.table.Call;
import org.clauseway.logic.unification.terms.Reified;
import org.clauseway.logic.unification.terms.Term;
import org.clauseway.logic.unification.terms.Unifiable;
import org.clauseway.pldb.relations.Answer;
import org.clauseway.pldb.relations.Answers;
import org.clauseway.pldb.relations.Relation;
import org.clauseway.vavr.collection.Array;
import org.clauseway.vavr.collection.IndexedSeq;

/**
 * The shared half of both posted-table kinds: the extension {(rᵢ, Cᵢ)} as
 * the Condition ⊕ᵢ (t=rᵢ ⊗ Cᵢ), folded per row, filtered by unification
 * compatibility, and read off as the verdict ladder. The kinds differ only
 * in how the extension arrives — a sync enumeration, or a produce drained
 * to the seal — and in how their grounding goals re-wake the record.
 */
final class Extension {

	private Extension() {
	}

	/**
	 * The probe as the call key, minted at the one reification site — WITHOUT
	 * the asker's own family. The question is "a region for (x,y)", and the
	 * posted table IS that question: transcribed into its own probe it would
	 * re-animate inside the producer's body and consume the entry mid-
	 * production (a wait-for cycle through the seal). The supports are no
	 * better a citizen: per-wake solver state in the key would fragment the
	 * memo. FD domains and nogoods on the args are the question's honest
	 * context and stay.
	 */
	static Fiber<Call<Relation>> probe(Knowledge pkg, Relation rel, Array<Term<?>> walked) {
		return Residues.about(pkg, lval(Tuple.ofAll(walked.map(Term::getObjectTerm).toJavaArray())))
				.map(key -> Call.of(rel, key._1,
						Residues.of(key._2.getTheories().remove(TableConstraints.class))));
	}

	/** One live disjunct: the row's image, its cells in Term vocabulary, its ⊕-folded condition. */
	@Value
	static class Row {
		Reified<?> image;
		IndexedSeq<Term<Object>> cells;
		Condition condition;
	}

	/** Answers ⊕-folded per row: duplicate derivations factor by distributivity. */
	static JoinMap<Reified<?>, Condition> fold(Iterable<Answer> answers) {
		return StreamSupport.stream(answers.spliterator(), false)
				.reduce(JoinMap.empty(Condition.RING),
						(l, r) -> l.append(r.getReified(), r.getCondition()).getOrElse(l),
						Exceptions.throwingBiOp(UnsupportedOperationException::new));
	}

	/** The disjuncts compatible with the walked tuple, in arrival order. */
	static List<Row> live(Array<Term<?>> walked, JoinMap<Reified<?>, Condition> extension,
			Theory<TableConstraints> theory) {
		return extension.order.toJavaStream()
				.flatMap(image -> Stream.of(image)
						.map(img -> Array.ofAll(Answers.positions(img)))
						.filter(cells -> compatible(theory, walked, cells))
						.map(cells -> new Row(image, cells, extension.members.get(image).get())))
				.collect(Collectors.toList());
	}

	/**
	 * The verdict ladder over the live disjuncts; {@code retire} is how a
	 * committing verdict removes the asking record from its theory.
	 */
	static Verdict verdict(Array<Term<?>> walked, List<Row> live,
			UnaryOperator<Theory<TableConstraints>> retire) {
		if (live.isEmpty()) {
			return Verdict.fail();
		}
		if (live.stream().anyMatch(row -> entailed(row, walked))) {
			return Verdict.subsumed();
		}
		if (walked.forAll(w -> w.isVal())) {
			return discharge(live, walked, retire);
		}
		if (live.size() == 1) {
			Row only = live.get(0);
			if (isUnconditional(only) && isGround(only)) {
				return Verdict.update((state, theory) ->
						TableConstraints.collapse(state, cast(theory), walked,
								Array.ofAll(only.getCells().map(Term::get))));
			}
			return discharge(live, walked, retire);
		}
		// the projection reads every condition as TRUE — its sound side. A
		// value pruned here has no supporting row even conditionally, and a
		// singleton binds because EVERY disjunct agrees on it, whatever its
		// condition holds. Nothing is dropped from the constraint: narrow
		// does not commit, the conditions stay in the extension and take
		// full effect at the committing restates. Supports stay plain value
		// sets — ⊕-annotating them per column would still approximate away
		// the rows' cross-column ⊗ structure, a richer lattice not yet earned
		return Verdict.update((state, theory) ->
				TableConstraints.narrowPatterns(state, cast(theory), walked,
						live.stream().map(Row::getCells).collect(Collectors.toList())));
	}

	/**
	 * The committing verdict: the constraint dissolves into the ⊕ of its live
	 * disjuncts, imposed — one branch per (entry, conjunct), each the same
	 * restate delivery uses, spliced into the run lane after quiescence.
	 */
	static Verdict discharge(List<Row> live, Array<Term<?>> walked,
			UnaryOperator<Theory<TableConstraints>> retire) {
		Goal body = branchRestates(live, walked)
				.reduce(Goal::or)
				.orElseGet(Goal::failure);
		return Verdict.update((state, theory) ->
				Update.applied(retire.apply(cast(theory))).withRun(body));
	}

	/** One goal per (entry, conjunct): the row restated whole at the walked anchor. */
	static Stream<Goal> branchRestates(List<Row> live, Array<Term<?>> walked) {
		Unifiable<?> anchor = lval(Tuple.ofAll(walked.map(Term::getObjectTerm).toJavaArray()));
		return live.stream()
				.flatMap(row -> row.getCondition().conjuncts().toJavaStream()
						.map(conjunct -> Residues.restate(row.getImage(), conjunct, anchor)));
	}

	private static boolean isUnconditional(Row only) {
		return Condition.ONE.equals(only.getCondition());
	}

	private static boolean isGround(Row only) {
		return only.getCells().forAll(cell -> cell.isVal());
	}

	/**
	 * An ENTAILED row discharges the constraint whole: its disjunct is 1
	 * under the current state and 1 ⊕ a = 1 — absorption, the disjunctive
	 * store's discharge doctrine in its new home.
	 */
	private static boolean entailed(Row row, Array<Term<?>> walked) {
		return isUnconditional(row)
				&& IntStream.range(0, walked.size())
				.allMatch(position -> imposesNothingAt(row, walked, position));
	}

	/** A ground cell must already match; a free cell's couplings must already agree. */
	private static boolean imposesNothingAt(Row row, Array<Term<?>> walked, int position) {
		Term<Object> cell = row.getCells().get(position);
		return cell.isVal() ?
				alreadyMatches(cell, walked.get(position)) :
				couplingsAlreadyAgree(row, walked, position);
	}

	/** The walked term holds this very value — unifying them would bind nothing. */
	private static boolean alreadyMatches(Term<Object> cell, Term<?> walked) {
		return walked.isVal() && cell.get().equals(walked.get());
	}

	/** Every earlier cell holding the SAME any already walks equal — the coupling is spent. */
	private static boolean couplingsAlreadyAgree(Row row, Array<Term<?>> walked, int position) {
		Term<Object> cell = row.getCells().get(position);
		return IntStream.range(0, position)
				.filter(earlier -> row.getCells().get(earlier).equals(cell))
				.allMatch(earlier -> walked.get(earlier).equals(walked.get(position)));
	}

	/**
	 * Unification compatibility with the walked tuple: a free cell admits
	 * everything (couplings are enforced by the restate at commit, not
	 * here — keeping a falsely compatible row only under-filters), a ground
	 * cell against a free column must survive that column's live support.
	 */
	private static boolean compatible(Theory<TableConstraints> theory, Array<Term<?>> walked, IndexedSeq<Term<Object>> cells) {
		for (int i = 0; i < walked.size(); i++) {
			Term<?> w = walked.get(i);
			Term<Object> cell = cells.get(i);
			if (!cell.isVal()) {
				continue;
			}
			if (w.isVal()) {
				if (!cell.get().equals(w.get())) {
					return false;
				}
				continue;
			}
			boolean excluded = TableConstraints.empty().getValue(theory, w)
					.map(support -> !support.admits(cell.get()))
					.getOrElse(false);
			if (excluded) {
				return false;
			}
		}
		return true;
	}

	@SuppressWarnings("unchecked")
	private static Theory<TableConstraints> cast(Theory<?> theory) {
		return (Theory<TableConstraints>) theory;
	}
}
