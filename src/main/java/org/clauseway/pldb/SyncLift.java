package org.clauseway.pldb;

// ABOUTME: The sync kind lifted into the async seam: answers enumerated inline
// ABOUTME: inside the claimed workforce — the inline cost the sync kind always had.

import lombok.Value;
import org.clauseway.functional.Nothing;
import org.clauseway.functional.fibers.Emitter;
import org.clauseway.functional.fibers.Fiber;
import org.clauseway.logic.solving.Call;
import org.clauseway.logic.solving.Answer;
import org.clauseway.pldb.relations.Relation;

/**
 * A sync {@link AnswerSource} worn as an {@link AnswerProducer}: the
 * enumeration runs inline inside whoever drives the produce — blocking that
 * frame exactly as the sync consumption always did, emissions and
 * completion following in order. Consumers that can serve a sync face
 * cheaply (a sealed table read) may recognize the lift and keep the sync
 * lane synchronous end to end.
 */
@Value
class SyncLift implements AnswerProducer {

	AnswerSource source;

	@Override
	public Fiber<Nothing> produce(Call<Relation> probe, Emitter<Answer<Relation>> emit) {
		Fiber<Nothing> emissions = Fiber.done(Nothing.nothing());
		for (Answer<Relation> answer : source.answers(probe)) {
			emissions = emissions.flatMap(emitted -> emit.emit(answer));
		}
		return emissions;
	}

	@Override
	public long estimate(Call<Relation> probe) {
		return source.estimate(probe);
	}

	@Override
	public String id() {
		return source.id();
	}
}
