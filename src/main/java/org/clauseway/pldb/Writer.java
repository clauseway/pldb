package org.clauseway.pldb;

// ABOUTME: The write face: Answer rows are the currency, Literal statements the
// ABOUTME: threshold sugar — both polarities, holes and guards refusing loudly.

import org.clauseway.pldb.relations.Relation;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;
import org.clauseway.logic.solving.Answer;
import org.clauseway.pldb.relations.Literal;

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
 * a store, and a transaction that both asserts and retracts one fact
 * refuses loudly: it has not decided what it believes.
 */
public interface Writer<S extends Writer<S>> {

	S asserting(List<Answer<Relation>> rows);

	S retracting(List<Answer<Relation>> rows);

	default S asserting(Collection<Literal> rows) {
		return asserting(facts(rows));
	}

	default S retracting(Collection<Literal> rows) {
		return retracting(facts(rows));
	}

	default S asserting(Literal... rows) {
		return asserting(Arrays.asList(rows));
	}

	default S retracting(Literal... rows) {
		return retracting(Arrays.asList(rows));
	}

	/** The threshold conversion: statements become rows, holes refuse. */
	static List<Answer<Relation>> facts(Collection<Literal> rows) {
		return rows.stream().map(Literal::fact).collect(Collectors.toList());
	}
}
