package org.clauseway.pldb;

// ABOUTME: The write face: Answer rows are the currency, Literal statements the
// ABOUTME: threshold sugar — both polarities, holes and guards refusing loudly.

import org.clauseway.pldb.relations.Relation;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.clauseway.logic.solving.Query;
import org.clauseway.logic.solving.Answer;
import org.clauseway.logic.solving.Selection;
import org.clauseway.pldb.relations.literals.External;
import org.clauseway.pldb.relations.literals.Literal;

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

	/** Every fact a select streams lands, driven by the query's own driver. */
	default S asserting(Selection<Answer<Relation>> facts) {
		return asserting(collected(facts));
	}

	/** Every fact a select streams leaves, driven by the query's own driver. */
	default S retracting(Selection<Answer<Relation>> facts) {
		return retracting(collected(facts));
	}

	/** Every row the stored literal answers leaves: the retract half of a refresh. */
	default S clearing(External<?> stored) {
		return retracting(Query.of(stored).select(stored.facts()));
	}

	/**
	 * The refresh of a stored derivation: every row the stored literal
	 * answers leaves, and every solution of the derived query lands as a
	 * fact of the stored literal ({@link External#facts()}), so the query
	 * is over the stored literal's own row. A derived cell the row does not
	 * share is refused as not ground before it lands. The query's driver
	 * runs the refresh. A row that leaves and lands unchanged cancels to
	 * nothing.
	 */
	default S replacing(External<?> stored, Query derived) {
		return clearing(stored).asserting(derived.select(stored.facts()));
	}

	/** The select driven to exhaustion and closed. */
	static List<Answer<Relation>> collected(Selection<Answer<Relation>> facts) {
		try (Stream<Answer<Relation>> all = facts.stream()) {
			return all.collect(Collectors.toList());
		}
	}

	/** The threshold conversion: statements become rows, holes refuse. */
	static List<Answer<Relation>> facts(Collection<? extends External<?>> rows) {
		return rows.stream().map(External::fact).collect(Collectors.toList());
	}
}
