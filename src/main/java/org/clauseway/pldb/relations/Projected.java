package org.clauseway.pldb.relations;

// ABOUTME: The projection marker: an ∃-projected column stated inline — a real
// ABOUTME: fresh variable everywhere except the builder, which sees the wrapper.

import org.clauseway.logic.unification.terms.Unifiable;

/**
 * A column the caller ∃-projects away, stated at the call site. The
 * marker IS a fresh variable — every term door delegates to one, so
 * inside a rule body it joins like any lvar — and only the builder
 * reads the wrapper type: a marked column drops out of the literal's
 * head into a generated projection rule, whose tabled cell folds
 * duplicates (set semantics) and whose posted form makes
 * {@code exclude} an honest ¬∃. Fresh per call: two markers never
 * couple. Outside any literal it refuses at the first walk.
 */
public final class Projected<T> extends MagicVar<T> {

	private Projected() {
	}

	@Override
	protected String unclaimed() {
		return "a projected() marker no literal claimed — a projection is a literal's column, not a variable";
	}

	public static <T> Unifiable<T> projected() {
		return new Projected<>();
	}
}
