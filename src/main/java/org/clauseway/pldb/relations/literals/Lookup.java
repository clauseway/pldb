package org.clauseway.pldb.relations.literals;

// ABOUTME: The probe minted at apply for a literal over an external relation: the arguments walked, the call
// ABOUTME: key reified at the one reification site, the answers delivered forked per condition conjunct.

import static org.clauseway.logic.unification.terms.LVal.lval;

import java.util.function.BiFunction;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.clauseway.functional.fibers.Cont;
import org.clauseway.functional.tuples.Tuple;
import org.clauseway.logic.goals.Goal;
import org.clauseway.logic.solving.Answer;
import org.clauseway.logic.solving.Call;
import org.clauseway.logic.solving.Residues;
import org.clauseway.logic.unification.Substitutions;
import org.clauseway.logic.unification.terms.Term;
import org.clauseway.logic.unification.terms.Unifiable;
import org.clauseway.pldb.relations.Relation;
import org.clauseway.vavr.collection.Array;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
final class Lookup {

	/** Mint the probe at apply, then hand it with its anchor to the dispatcher. */
	static Goal of(Literal<?> lit, BiFunction<Call<Relation>, Unifiable<?>, Goal> dispatcher) {
		return s -> Cont.defer(() -> {
			Unifiable<?> anchor = buildAnchor(s.substitution(), lit.getArgs());
			return Residues.about(s, anchor)
					.map(key -> dispatcher
							.apply(Call.of(lit.getRelation(), key._1, key._2), anchor).apply(s));
		});
	}

	/** One answer, forked per condition conjunct, each restated at the anchor. */
	static Goal deliver(Answer<Relation> answer, Unifiable<?> anchor) {
		return answer.getCondition().conjuncts().toJavaStream()
				.map(conjunct -> Residues.restate(answer.getReified(), conjunct, anchor))
				.reduce(Goal::or)
				.orElseGet(Goal::failure);
	}

	@SuppressWarnings("ConfusingArgumentToVarargsMethod")
	private static Unifiable<?> buildAnchor(Substitutions s, Array<Unifiable<?>> query) {
		return lval(Tuple.ofAll(query.toJavaStream()
				.map(u -> (Unifiable<?>) s.walk(u))
				.map(Unifiable::getObjectTerm)
				.toArray(Term[]::new)));
	}
}
