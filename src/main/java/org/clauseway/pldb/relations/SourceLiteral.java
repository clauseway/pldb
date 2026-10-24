package org.clauseway.pldb.relations;

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
import org.clauseway.pldb.AnswerSource;
import org.clauseway.pldb.constraints.TableConstraints;
import org.clauseway.vavr.collection.Array;

@Getter
@RequiredArgsConstructor
final class SourceLiteral<R extends Schema<R>> implements External<R> {
	private final Relation rel;
	private final Array<Unifiable<?>> args;
	private final R row;
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
		return TableConstraints.posted(source, rel, args.toJavaList());
	}

	@Override
	public long estimate(Call<Relation> call) {
		return source.estimate(call);
	}

	@Override
	public String toString() {
		return rel.toString() + args;
	}
}
