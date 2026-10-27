package org.clauseway.pldb.relations;

// ABOUTME: The marker-variable base: a fresh lvar in every respect except to the
// ABOUTME: ONE consumer that recognizes the wrapper and reads its payload.

import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.clauseway.logic.unification.terms.LVar;
import org.clauseway.logic.unification.terms.Name;
import org.clauseway.logic.unification.terms.Unifiable;

/**
 * A marker riding a typed argument slot: every term door delegates to a
 * fresh variable, so anywhere but its consumer the marker IS an
 * ordinary lvar — passed into a body it joins, unifies, and projects
 * like any free — and exactly one consumer instanceof-checks the
 * wrapper to read what it carries ({@link Projected}'s claim, a codec,
 * an index declaration). The defining method's signature type-checks
 * the association for free: a marker is a {@code Unifiable<T>}, so it
 * cannot land in a differently-typed slot. Fresh per mint: two markers
 * never couple.
 *
 * The consumer {@link #claim}s the marker; a marker no consumer claimed
 * is a mistake, not a variable, and the engine's first walk of it — any
 * term door — refuses by what the marker is ({@link #unclaimed}). Every
 * legal use is claimed before anything walks it: a literal claims at its
 * building, a codec at its registration, and walking happens at the solve.
 */
public abstract class MagicVar<T> implements Unifiable<T> {

	private final Unifiable<T> variable = LVar.lvar();
	private final AtomicBoolean claimed = new AtomicBoolean();

	/**
	 * One-shot: the FIRST consumer to claim owns the marker — for a
	 * projection, the literal the caller wrote it into, because a defining
	 * method declares its args before its body builds. Later appearances
	 * (the marker forwarded into body literals) read it as the plain
	 * variable it delegates to, so a marker used as a join variable stays
	 * ONE variable.
	 */
	public final boolean claim() {
		return claimed.compareAndSet(false, true);
	}

	/** What this marker is and who should have claimed it, for the refusal when no consumer did. */
	protected abstract String unclaimed();

	private Unifiable<T> claimedVariable() {
		if (!claimed.get()) {
			throw new IllegalStateException(variable + " is " + unclaimed());
		}
		return variable;
	}

	@Override
	public final Optional<LVar<T>> asVar() {
		return claimedVariable().asVar();
	}

	@Override
	public final Optional<Name<T>> asName() {
		return claimedVariable().asName();
	}

	@Override
	public final LVar<T> getVar() {
		return claimedVariable().getVar();
	}

	@Override
	public final String toString() {
		return variable.toString();
	}
}
