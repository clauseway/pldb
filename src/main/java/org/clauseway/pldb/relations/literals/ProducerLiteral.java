package org.clauseway.pldb.relations.literals;

// ABOUTME: The literal over a producer: the async kind, emissions delivered as the cell grows and the seal
// ABOUTME: ending the branch, imposed as a parking table constraint under exclude, estimated by the producer.

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.clauseway.logic.constraints.Posting;
import org.clauseway.logic.goals.Goal;
import org.clauseway.logic.solving.Call;
import org.clauseway.logic.unification.terms.Unifiable;
import org.clauseway.pldb.relations.Relation;
import org.clauseway.pldb.relations.Schema;

import org.clauseway.pldb.AnswerProducer;
import org.clauseway.pldb.constraints.TableConstraints;
import org.clauseway.vavr.collection.Array;

@Getter
@RequiredArgsConstructor
final class ProducerLiteral<S extends Schema<S>> implements External<S> {
	private final Relation relation;
	private final Array<Unifiable<?>> args;
	private final S schema;
	private final AnswerProducer producer;

	@Override
	public Goal read() {
		return Lookup.of(this, (probe, anchor) -> st -> k -> producer.produce(probe,
				answer -> Lookup.deliver(answer, anchor).apply(st).apply(k)));
	}

	@Override
	public Posting posted() {
		return TableConstraints.posted(producer, relation, args.toJavaList());
	}

	@Override
	public long estimate(Call<Relation> call) {
		return producer.estimate(call);
	}

	@Override
	public String toString() {
		return relation.toString() + args;
	}
}
