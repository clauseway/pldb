package org.clauseway.pldb.relations.literals;

// ABOUTME: The literal over a source: the sync kind, the source's answers to the probe enumerated inline,
// ABOUTME: imposed as a table constraint under exclude, estimated by the source.

import java.util.stream.StreamSupport;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.clauseway.logic.constraints.Posting;
import org.clauseway.logic.goals.Goal;
import org.clauseway.logic.solving.Call;
import org.clauseway.logic.solving.Residues;
import org.clauseway.logic.unification.terms.Unifiable;
import org.clauseway.pldb.relations.Relation;
import org.clauseway.pldb.relations.Schema;

import org.clauseway.pldb.AnswerSource;
import org.clauseway.pldb.constraints.TableConstraints;
import org.clauseway.vavr.collection.Array;

@Getter
@RequiredArgsConstructor
final class SourceLiteral<S extends Schema<S>> implements External<S> {
	private final Relation relation;
	private final Array<Unifiable<?>> args;
	private final S row;
	private final AnswerSource source;

	@Override
	public Goal read() {
		return Lookup.of(this, (probe, anchor) ->
				StreamSupport.stream(source.answers(probe).spliterator(), false)
						.flatMap(answer -> answer.getCondition().conjuncts().toJavaStream()
								.map(conjunct -> (Goal) Residues.restate(answer.getReified(), conjunct, anchor)))
						.reduce(Goal::or)
						.orElseGet(Goal::failure));
	}

	@Override
	public Posting posted() {
		return TableConstraints.posted(source, relation, args.toJavaList());
	}

	@Override
	public long estimate(Call<Relation> call) {
		return source.estimate(call);
	}

	@Override
	public String toString() {
		return relation.toString() + args;
	}
}
