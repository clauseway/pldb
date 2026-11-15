package org.clauseway.pldb.relations.literals;

// ABOUTME: The literal a rule defines: read as an ordinary tabled call in the solve's table, where its
// ABOUTME: recursion seals; imposed under exclude over the same table; priced by it; never written.

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.clauseway.functional.tuples.Tuple;
import org.clauseway.logic.constraints.Posting;
import org.clauseway.logic.goals.Goal;
import org.clauseway.logic.goals.Knowledge;
import org.clauseway.logic.solving.Call;
import org.clauseway.logic.tabling.Tabling;
import org.clauseway.logic.tabling.table.Table;
import org.clauseway.logic.unification.terms.Unifiable;
import org.clauseway.pldb.relations.Relation;
import org.clauseway.pldb.relations.Schema;

import org.clauseway.pldb.GoalProducer;
import org.clauseway.pldb.constraints.TableConstraints;
import org.clauseway.vavr.collection.Array;

/**
 * The goal reading is a tabled call in the SOLVE's table; the imposition
 * reads the extension through a {@link GoalProducer} over that same table,
 * so both readings share its entries.
 */
@Getter
@RequiredArgsConstructor
class RuleLiteral<S extends Schema<?>> implements Literal<S> {
	private final Relation relation;
	private final Array<Unifiable<?>> args;
	private final S row;
	private final Goal body;

	@Override
	public Goal read() {
		return Tabling.call(relation, Tuple.ofAll(args.map(Unifiable::getObjectUnifiable).toJavaArray()), () -> body);
	}

	@Override
	public Posting posted() {
		return TableConstraints.postedRule(relation, body, args.toJavaList());
	}

	@Override
	public long estimate(Call<Relation> call) {
		return Long.MAX_VALUE;
	}

	/**
	 * Priced by the solve's table: a sealed entry covering the call counts
	 * its answers. Until one exists, or outside a solve, the price is the
	 * substitution-level one.
	 */
	@Override
	public long answers(Knowledge p) {
		long sealed = p.getStores().get(Table.class)
				.map(Table.class::cast)
				.map(table -> GoalProducer.of(relation, body, args.toJavaList(), table).estimate(probe(p.substitution())))
				.getOrElse(Long.MAX_VALUE);
		return Math.min(sealed, answers(p.substitution()));
	}

	@Override
	public String toString() {
		return relation.toString() + args;
	}
}
