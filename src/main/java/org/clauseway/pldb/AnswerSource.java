package org.clauseway.pldb;

// ABOUTME: The seam's sync kind: answers for a call key enumerated inline, the
// ABOUTME: closed in-process tier — in-memory data, sealed cells, the GAC/trial lane.

import org.clauseway.logic.solving.Condition;
import org.clauseway.logic.solving.Call;
import org.clauseway.logic.solving.Answer;
import org.clauseway.pldb.relations.Relation;

/**
 * A source of answers: what a relation lookup consumes. The probe IS the
 * call key — relation identity, the reified argument image (ground
 * positions bound, anys free), and the region as residues — and an answer
 * is the cell's entry shape: a reified row with its {@link Condition},
 * ground rows at {@link Condition#ONE}.
 *
 * <p>This is the seam's SYNC kind, the closed in-process tier: answers
 * enumerate inline on the caller's thread. The region is ADVISORY — a
 * source may consult it to narrow (compile parts into its own query
 * language), and ignoring it, wholly or per family, is always correct:
 * a source may only ever OVER-deliver for its probe, never under-deliver;
 * narrowing the source did not apply stays local, enforced by propagation
 * over the returned rows.
 */
public interface AnswerSource {

	Iterable<Answer<Relation>> answers(Call<Relation> probe);

	/**
	 * Upper bound on the answers {@link #answers} would yield — the
	 * planner's order function (logic's optimizer.md §3). Always
	 * synchronous, like all pricing. Exposure, not computation: the default
	 * counts, backends with sized buckets should override.
	 */
	default long estimate(Call<Relation> probe) {
		Iterable<Answer<Relation>> bucket = answers(probe);
		if (bucket instanceof java.util.Collection) {
			return ((java.util.Collection<?>) bucket).size();
		}
		long n = 0;
		for (@SuppressWarnings("unused") Answer<Relation> a : bucket) {
			n++;
		}
		return n;
	}

	/**
	 * The source's identity, as knowledge: two posts of one lookup are the
	 * same constraint exactly when their sources answer for the same data,
	 * and this string is what says so. The default is object identity —
	 * right for in-memory values, where each database IS its data; a
	 * backend reachable through many handles must override with a declared
	 * id, or equal lookups against it will read as distinct knowledge.
	 */
	default String id() {
		return Integer.toHexString(System.identityHashCode(this));
	}
}
