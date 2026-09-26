package org.clauseway.pldb;

// ABOUTME: An AnswerProducer that drives a goal into an injected Table — the one
// ABOUTME: produce bridge: rule extensions for the constraint side, table supplied
// ABOUTME: by whoever owns the residence.

import static org.clauseway.logic.unification.terms.LVal.lval;

import java.util.List;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.clauseway.functional.Nothing;
import org.clauseway.functional.fibers.Emitter;
import org.clauseway.functional.fibers.Fiber;
import org.clauseway.functional.tuples.Tuple;
import org.clauseway.logic.goals.Conjunction;
import org.clauseway.logic.goals.Goal;
import org.clauseway.logic.goals.Knowledge;
import org.clauseway.logic.tabling.Tabling;
import org.clauseway.logic.solving.Condition;
import org.clauseway.logic.solving.Residues;
import org.clauseway.logic.solving.Call;
import org.clauseway.logic.tabling.table.Table;
import org.clauseway.logic.tabling.table.TableEntry;
import org.clauseway.logic.unification.terms.Unifiable;
import org.clauseway.pldb.relations.Answer;
import org.clauseway.pldb.relations.Relation;
import org.clauseway.vavr.collection.Array;

/**
 * The produce face of a rule over an INJECTED table: the anchor is the
 * captured heads — the probe image restates onto the variables the body
 * speaks, the tabled call keys off their resulting bindings (alpha-aligned
 * with goal-side consumption at the same probe), and deliveries image back
 * over them; sound on the clean package because its lineage is disjoint.
 * Residence is the injector's decision: hand in the solve's table and the
 * entry this production fills is the same one every goal-side consumer
 * reads — one production, two readings.
 */
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public final class GoalProducer implements AnswerProducer {

	private final Relation rel;
	private final Goal rule;
	private final Array<Unifiable<?>> heads;
	private final Table table;

	public static GoalProducer of(Relation rel, Goal rule, List<Unifiable<?>> heads, Table table) {
		return new GoalProducer(rel, rule, Array.ofAll(heads), table);
	}

	@Override
	public Fiber<Nothing> produce(Call<Relation> probe, Emitter<Answer> emit) {
		Unifiable<Object> anchor = lval((Object) Tuple.ofAll(heads.map(Unifiable::getObjectTerm).toJavaArray()));
		Goal seeded = Conjunction.of(
				Residues.restate(probe.getArguments(), probe.getResidues(), anchor),
				Tabling.call(rel, Tuple.ofAll(heads.map(Unifiable::getObjectUnifiable).toJavaArray()), () -> rule));
		return seeded.apply(Knowledge.empty().withStore(table)).apply(answerPkg ->
				Residues.all(answerPkg, anchor).flatMap(answer ->
						emit.emit(Answer.of(rel, answer._1, Condition.of(answer._2)))));
	}

	@Override
	public long estimate(Call<Relation> probe) {
		TableEntry<Object> sealed = table.findSealedSubsumer(
				Call.of(rel, probe.getArguments(), probe.getResidues()));
		return sealed != null ? sealed.getAnswerCount() : Long.MAX_VALUE;
	}

	@Override
	public String id() {
		return "rule:" + rel.getName();
	}

	@Override
	public String toString() {
		return id();
	}
}
