package org.clauseway.pldb;

// ABOUTME: The write face: Answer rows are the currency, Literal statements the
// ABOUTME: threshold sugar — both polarities, holes and guards refusing loudly.

import org.clauseway.pldb.relations.Relation;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;
import org.clauseway.functional.fibers.schedulers.BreadthFirstScheduler;
import org.clauseway.logic.goals.Goal;
import org.clauseway.logic.solving.Query;
import org.clauseway.logic.solving.Answer;
import org.clauseway.pldb.relations.literals.Question;
import org.clauseway.pldb.relations.literals.External;
import org.clauseway.pldb.relations.literals.Literal;
import org.clauseway.pldb.relations.Schema;

/**
 * A value that accepts writes. The PRIMITIVE doors speak {@link Answer}
 * rows — the one data currency — and own the strictness: a wide cell
 * refuses by relation and column, a guarded row refuses toward the
 * explicit choice ({@link Answer#unconditional()}). The {@link Literal}
 * doors are the statement face: minted by the schema functions,
 * converted at the threshold ({@link Literal#fact()} refuses holes),
 * always ground and unconditional by construction. {@link #retracting}
 * removes BY FACT — the membership claim leaves whole, every physical
 * duplicate with it; retracting the absent is a set-semantics no-op at
 * a store, and a transaction that retracts a fact it staged, or asserts
 * one it staged to remove, cancels it: the delta is a signed set.
 */
public interface Writer<S extends Writer<S>> {

	S asserting(List<Answer<Relation>> rows);

	S retracting(List<Answer<Relation>> rows);

	default S asserting(Collection<? extends External<?>> rows) {
		return asserting(facts(rows));
	}

	default S retracting(Collection<? extends External<?>> rows) {
		return retracting(facts(rows));
	}

	default S asserting(External<?>... rows) {
		return asserting(Arrays.asList(rows));
	}

	default S retracting(External<?>... rows) {
		return retracting(Arrays.asList(rows));
	}

	/**
	 * Every row the stored literal answers leaves: the retract half of a
	 * refresh. The question is driven to exhaustion on the default fair
	 * scheduler; a caller wanting another drives {@link Question#select} itself.
	 */
	default S clearing(External<?> stored) {
		return retracting(rows(stored, stored));
	}

	/**
	 * The refresh of a stored derivation: every row the stored literal
	 * answers leaves, and every row the derived literal answers lands in
	 * the stored relation's shape. The template is the stored literal's own
	 * row over no source, so both literals must be built over the same row;
	 * a derived cell the row does not share rides wide and the door refuses
	 * it. A row that leaves and lands unchanged cancels to nothing.
	 */
	default <R extends Schema<R>> S replacing(External<R> stored, Literal<R> derived) {
		return clearing(stored).asserting(rows(derived, stored));
	}

	/** The question's answers in the template's shape, driven to exhaustion. */
	static List<Answer<Relation>> rows(Goal question, External<?> template) {
		return new BreadthFirstScheduler<>(Question.select(Query.of(question), template)).get();
	}

	/** The threshold conversion: statements become rows, holes refuse. */
	static List<Answer<Relation>> facts(Collection<? extends External<?>> rows) {
		return rows.stream().map(External::fact).collect(Collectors.toList());
	}
}
