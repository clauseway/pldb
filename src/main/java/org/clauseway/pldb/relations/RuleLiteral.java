package org.clauseway.pldb.relations;

// ABOUTME: The literal a rule defines: read as an ordinary tabled call in the solve's table, where its
// ABOUTME: recursion seals; imposed under exclude through a private table over the body; never written.

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.clauseway.functional.tuples.Tuple;
import org.clauseway.logic.constraints.Posting;
import org.clauseway.logic.goals.Goal;
import org.clauseway.logic.solving.Call;
import org.clauseway.logic.tabling.Tabling;
import org.clauseway.logic.unification.terms.Unifiable;
import org.clauseway.pldb.GoalProducer;
import org.clauseway.pldb.constraints.TableConstraints;
import org.clauseway.vavr.collection.Array;

/**
 * The goal reading is a tabled call in the SOLVE's table; the imposition
 * reads the extension through a {@link GoalProducer} over a per-posting
 * private table — the memo and the world it memoizes share one closure.
 */
@Getter
@RequiredArgsConstructor
class RuleLiteral<R extends Schema<R>> implements Literal<R> {
	private final Relation rel;
	private final Array<Unifiable<?>> args;
	private final R row;
	private final Goal body;

	@Override
	public Goal read() {
		return Tabling.call(rel, Tuple.ofAll(args.map(Unifiable::getObjectUnifiable).toJavaArray()), () -> body);
	}

	@Override
	public Posting posted() {
		return TableConstraints.postedRule(rel, body, args.toJavaList());
	}

	@Override
	public long estimate(Call<Relation> call) {
		return Long.MAX_VALUE;
	}

	@Override
	public String toString() {
		return rel.toString() + args;
	}
}
