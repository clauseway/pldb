package org.clauseway.pldb.sql;

// ABOUTME: The adapter-side compiler a user registers per constraint family:
// ABOUTME: one atom in, optionally one WHERE predicate out — weaker or equal.

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.clauseway.logic.constraints.store.Atom;
import org.clauseway.logic.unification.terms.Term;
import org.clauseway.pldb.sql.compiler.SqlPredicate;

/**
 * Compiles one constraint atom into the adapter's predicate vocabulary.
 * Registered on a source per FACTOR CLASS, so user-created constraint
 * families push down without the adapter enumerating them; an unregistered
 * family simply stays local — silence, not error.
 *
 * <p>THE ONE LAW: the compiled predicate must be entailed by the atom —
 * weaker or equal, never stronger. A predicate that selects fewer rows
 * than the atom admits makes the source under-deliver, and lost answers
 * are silent. Returning {@link Optional#empty()} for anything uncertain
 * is always correct: the atom's narrowing stays local, enforced by
 * propagation over the returned rows.
 *
 * <p>Atoms name the probe's frees by their reified terms; the resolver
 * answers with the column(s) the term OCCUPIES IN THE IMAGE, and ground
 * terms with nothing — a compiler reads its operands through it and
 * never sees tables. A coupled variable occupies several columns:
 * {@link ColumnResolver#columnsOf} lists them all, and conjoining a
 * constraint across every occurrence is lawful — rows disagreeing
 * between coupled columns are never valid answers, so the conjunct
 * drops only what the local coupling filter would drop.
 */
public interface SqlCompiler {

	Optional<SqlPredicate> compile(Atom<?> atom, ColumnResolver columns);

	interface ColumnResolver {
		/** The FIRST column the term occupies — for one-column-per-operand shapes. */
		Optional<String> columnOf(Term<?> term);

		/** EVERY column the term occupies — for fan-out conjuncts over couplings. */
		default List<String> columnsOf(Term<?> term) {
			return columnOf(term)
					.map(Collections::singletonList)
					.orElse(Collections.emptyList());
		}

		/** Whether the named column's cells are options (it may hold NULL); false when unknown. */
		default boolean optional(String column) {
			return false;
		}
	}
}
