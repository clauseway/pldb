package org.clauseway.pldb;

// ABOUTME: The seam's async kind: a source IS the produce function — emissions on
// ABOUTME: the caller's scheduler, completion is the seal.

import org.clauseway.functional.Nothing;
import org.clauseway.functional.fibers.Emitter;
import org.clauseway.functional.fibers.Fiber;
import org.clauseway.logic.solving.Call;
import org.clauseway.logic.solving.Answer;
import org.clauseway.pldb.relations.Relation;

/**
 * The seam's ASYNC kind — the primary kind for anything that computes or
 * fetches: a source IS the produce function from a call key to an emission
 * of answers, and COMPLETION IS THE SEAL. The workforce runs on the
 * CALLER's scheduler: no source owns a driver, forked work continues while
 * the source prepares results elsewhere, and an examination that parks
 * waits on its own branch only. The over-delivery law is unchanged: emit
 * every answer matching the probe (the region is advisory), never fewer.
 *
 * <p>The sync {@link AnswerSource} lifts into this kind at the composition
 * point ({@link #of} — the two-lane doctrine's one legal direction); the
 * fiber kind never masquerades as sync.
 */
public interface AnswerProducer {

	Fiber<Nothing> produce(Call<Relation> probe, Emitter<Answer<Relation>> emit);

	/** Upper bound on produce's emissions — pricing, always synchronous. */
	default long estimate(Call<Relation> probe) {
		return Long.MAX_VALUE;
	}

	/** Knowledge identity: same id ⟺ answers for the same data. */
	default String id() {
		return Integer.toHexString(System.identityHashCode(this));
	}

	/** The sync kind lifted: its answers enumerated inside the claimed workforce. */
	static AnswerProducer of(AnswerSource source) {
		return new SyncLift(source);
	}
}
