package org.clauseway.pldb.relations.literals;

// ABOUTME: The question front door: a goal plus result-row templates, answers as
// ABOUTME: Answers WITH their guards — rows a caller reads, a persist lands, or a
// ABOUTME: retract removes; the same extraction the produce seam mints with.

import org.clauseway.logic.solving.Answer;
import static org.clauseway.logic.unification.terms.LVal.lval;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.clauseway.functional.Nothing;
import org.clauseway.functional.fibers.Cont;
import org.clauseway.functional.fibers.Fiber;
import org.clauseway.functional.tuples.Tuple;
import org.clauseway.logic.goals.Exhaustion;
import org.clauseway.logic.goals.Goal;
import org.clauseway.logic.solving.Condition;
import org.clauseway.logic.solving.Query;
import org.clauseway.logic.unification.terms.Term;
import org.clauseway.logic.unification.terms.Unifiable;
import org.clauseway.pldb.relations.Relation;
import org.clauseway.pldb.relations.Schema;
import org.clauseway.pldb.relations.Answers;

import org.clauseway.vavr.collection.Array;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class Question {

	/**
	 * The (question, schemas) shape: the question is a full logical
	 * program — any joins, negation, or domains — and each schema is a
	 * template literal minted by the relation's own function, naming the
	 * row shape and, through the lvars it shares with the question, which
	 * answer values land in which columns. Every answer grounds every
	 * template into one {@link Answer} per schema — the whole cluster
	 * from one derivation, every row carrying the derivation's
	 * {@link Condition} — and the rows serve every consumer alike: a
	 * caller reads them as typed rows ({@link Answers#get}), a persist
	 * stages them, a retract stages their removal. Zero answers stream
	 * nothing. A template cell the answer leaves free rides as a WIDE
	 * cell — the write doors, not the select, refuse it. The result is a
	 * {@link Fiber}: the collection of the question driven to EXHAUSTION
	 * (cold and finite, structurally), awaiting whatever engine the
	 * caller constructs — the scheduler is deliberately not chosen here.
	 */

	/** A projected() member in a row a question lands in: the marker belongs to a literal's definition. */
	private static void refuseProjected(Schema<?> row) {
		int position = 0;
		for (Unifiable<?> key : row.keys()) {
			if (Projection.marker(key).isPresent()) {
				throw new IllegalStateException("member '" + row.columns().get(position).getName()
						+ "' is projected() — a projection belongs to a literal's definition; a row a question"
						+ " lands in needs a variable or a value in every member");
			}
			position++;
		}
	}

	public static Fiber<List<Answer<Relation>>> select(Query question, External<?>... schemas) {
		for (External<?> schema : schemas) {
			refuseProjected(schema.getRow());
		}
		Array<Unifiable<?>> variables = variablesOf(schemas);
		return Exhaustion.collected(rows(question,
				lval(Tuple.ofAll(variables.map(Unifiable::getObjectTerm).toJavaArray())),
				variables, schemas));
	}

	/**
	 * Every answer package's cluster, one row per continuation call — the
	 * extraction the produce seam mints conditional answers with
	 * ({@code Residues.all} at the anchor, walking + slot
	 * canonicalization), collected instead of emitted.
	 */
	private static Cont<Answer<Relation>, Nothing> rows(Query question, Unifiable<?> anchor,
			Array<Unifiable<?>> variables, External<?>[] schemas) {
		return Cont.suspend(k -> question.run()
				.apply(answerPkg -> Answer.capture(anchor, answerPkg, anchor)
						.flatMap(answer -> facts(
								bind(variables, Array.ofAll(Answers.positions(answer.getReified()))),
								answer.getCondition(), schemas)
								.map(k)
								.reduce(Fiber.done(Nothing.nothing()),
										(delivered, next) -> delivered.flatMap(nothing -> next)))));
	}

	/** The templates' distinct variables, in birth order — the solve tuple. */
	private static Array<Unifiable<?>> variablesOf(External<?>[] schemas) {
		return Arrays.stream(schemas)
				.flatMap(schema -> schema.getArgs().toJavaStream())
				.flatMap(arg -> arg.asVar()
						.map(Stream::<Unifiable<?>>of)
						.orElseGet(Stream::empty))
				.sorted(Comparator.comparing(v -> v.asVar().get().getBirth()))
				.distinct()
				.collect(Array.collector());
	}

	/**
	 * A free variable reifies to Any — a Term, never a value — so the map
	 * speaks Term and the groundness check below owns the distinction.
	 */
	private static Map<Unifiable<?>, Term<?>> bind(
			Array<Unifiable<?>> variables, Array<?> answer) {
		return IntStream.range(0, variables.size())
				.boxed()
				.collect(Collectors.toMap(variables::get, i -> (Term<?>) answer.get(i)));
	}

	/**
	 * One answer, every schema: the whole cluster this derivation names,
	 * every row under the derivation's one guard.
	 */
	private static Stream<Answer<Relation>> facts(Map<Unifiable<?>, Term<?>> bound,
			Condition condition, External<?>[] schemas) {
		return Arrays.stream(schemas)
				.map(schema -> Answer.of(schema.getRelation(),
						Answers.image(IntStream.range(0, schema.getArgs().length())
								.mapToObj(i -> cell(schema, i, bound))
								.collect(Collectors.<Term<?>> toList())),
						condition));
	}

	/**
	 * The template cell under this answer: its own constant, or whatever
	 * the answer holds at the shared variable — a value, or the Any a
	 * free cell rides as.
	 */
	private static Term<?> cell(External<?> schema, int position, Map<Unifiable<?>, Term<?>> bound) {
		Unifiable<?> arg = schema.getArgs().get(position);
		return arg.isVal() ? arg : bound.get(arg);
	}
}
