package org.clauseway.pldb.sql;

// ABOUTME: An AnswerSource that caches its delegate subsumptively: answers land in a
// ABOUTME: pool, the ledger records probes as calls, Call.subsumes proves coverage.

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.clauseway.logic.solving.Call;
import org.clauseway.pldb.AnswerSource;
import org.clauseway.pldb.inmemory.AnswerStore;
import org.clauseway.logic.solving.Answer;
import org.clauseway.pldb.relations.Relation;

/**
 * Subsumptive reuse over any delegate source — call subsumption at the data
 * boundary: wide serves narrow, never the reverse. Fetched answers land in
 * an {@link AnswerStore} pool — idempotently by the store's own law
 * (duplicate images ⊕-fold), conditions kept whole — and the ledger
 * records each fetch AS ITS CALL. Soundness rides the seam's own law: the delegate
 * must return every answer matching the probe, so everything in the probe's
 * region landed and the claim is honest REGARDLESS of how much of the region
 * the delegate actually enforced — which is why the recorded probe is
 * EXACTLY the call passed through, one object, one line. A probe is served
 * locally only on proof: a recorded call that {@link Call#subsumes} it —
 * relation identity, argument subsumption, residue entailment, the one
 * containment judgment the key stack already owns. Anything short of proof
 * re-fetches, idempotently.
 *
 * <p>Estimates delegate when uncovered, so a backend with real statistics
 * flows through; covered probes price exactly from the pool.
 */
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public final class CachingAnswerSource implements AnswerSource {

	private final AnswerSource delegate;

	private AnswerStore cache = AnswerStore.empty();
	private final Map<Relation, List<Call<Relation>>> covered = new HashMap<>();

	public static CachingAnswerSource over(AnswerSource delegate) {
		return new CachingAnswerSource(delegate);
	}

	@Override
	public String id() {
		return delegate.id();
	}

	boolean isEmpty() {
		return covered.isEmpty();
	}

	@Override
	public synchronized Iterable<Answer<Relation>> answers(Call<Relation> probe) {
		if (!covers(probe)) {
			// landing is idempotent by the store's own law: a duplicate image
			// ⊕-folds inert, and a conditional answer lands WITH its guard —
			// the pool speaks the seam's whole entry shape
			cache = cache.withAll(probe.getRelation(), delegate.answers(probe));
			covered.computeIfAbsent(probe.getRelation(), r -> new ArrayList<>())
					.add(probe);
		}
		return cache.answers(pattern(probe));
	}

	@Override
	public synchronized long estimate(Call<Relation> probe) {
		return covers(pattern(probe)) ?
				cache.estimate(probe) :
				delegate.estimate(probe);
	}

	/** The probe widened to its pattern: region dropped, the pool's serve key. */
	private static Call<Relation> pattern(Call<Relation> probe) {
		return Call.of(probe.getRelation(), probe.getArguments());
	}

	private boolean covers(Call<Relation> probe) {
		return covered.getOrDefault(probe.getRelation(), Collections.emptyList())
				.stream()
				.anyMatch(prior -> prior.subsumes(probe));
	}

	@Override
	public String toString() {
		return "caching(" + delegate + ")";
	}
}
