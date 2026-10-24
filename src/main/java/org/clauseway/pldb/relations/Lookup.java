package org.clauseway.pldb.relations;

// ABOUTME: The probe minted at apply for a literal over an external relation: the arguments walked, the call
// ABOUTME: key reified at the one reification site, the answers delivered forked per condition conjunct.

import static org.clauseway.logic.unification.terms.LVal.lval;

import java.util.function.BiFunction;
import java.util.stream.Stream;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.clauseway.functional.fibers.Cont;
import org.clauseway.functional.fibers.Fiber;
import org.clauseway.functional.tuples.Tuple;
import org.clauseway.logic.goals.Goal;
import org.clauseway.logic.solving.Answer;
import org.clauseway.logic.solving.Call;
import org.clauseway.logic.solving.Residues;
import org.clauseway.logic.unification.Substitutions;
import org.clauseway.logic.unification.terms.Unifiable;
import org.clauseway.vavr.collection.Array;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
final class Lookup {

	/** Mint the probe at apply, then hand it with its anchor to the dispatcher. */
	static Goal of(Literal<?> lit, BiFunction<Call<Relation>, Unifiable<?>, Goal> dispatcher) {
		return s -> Cont.defer(() -> substituteQueryItems(s.substitution(), lit.getArgs())
				.flatMap(q -> {
					Unifiable<?> anchor = lval(Tuple.ofAll(q.map(Unifiable::getObjectTerm).toJavaArray()));
					return Residues.about(s, anchor)
							.map(key -> dispatcher.apply(Call.of(lit.getRel(), key._1, key._2), anchor).apply(s));
				}));
	}

	/** One answer, forked per condition conjunct, each restated at the anchor. */
	static Goal deliver(Answer<Relation> answer, Unifiable<?> anchor) {
		return answer.getCondition().conjuncts().toJavaStream()
				.map(conjunct -> (Goal) Residues.restate(answer.getReified(), conjunct, anchor))
				.reduce(Goal::or)
				.orElseGet(Goal::failure);
	}

	private static Fiber<Array<Unifiable<?>>> substituteQueryItems(Substitutions s, Array<Unifiable<?>> query) {
		return query.toJavaStream()
				.map(u -> (Unifiable<?>) s.walk(u))
				.map(Stream::of)
				.map(Fiber::done)
				.reduce((acc, c) -> Fiber.zip(acc, c).map(t -> t.apply(Stream::concat)))
				.orElseGet(() -> Fiber.done(Stream.empty()))
				.map(q -> q.collect(Array.collector()));
	}
}
