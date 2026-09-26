package org.clauseway.pldb.relations;

// ABOUTME: The one data row: a relation, its reified image — ground or wide — and
// ABOUTME: the Condition it holds under; reads keep the guard, write doors refuse it.

import java.util.List;
import java.util.Optional;
import lombok.Value;
import org.clauseway.functional.tuples.Tuple;
import org.clauseway.functional.tuples.Tuple2;
import org.clauseway.logic.solving.Condition;
import org.clauseway.logic.unification.terms.Reified;
import org.clauseway.logic.unification.terms.Term;
import org.clauseway.vavr.control.Try;

/**
 * One row of data, whole: the {@link Relation} it belongs to, its reified
 * image — a ground cell is a value, a free cell an Any (the ∀-schema
 * marking) — and the {@link Condition} it holds under, ground facts at
 * {@link Condition#ONE}. Every consumer speaks this shape: a query
 * reads it (typed access via {@link #get}), a persist lands it, a
 * retract removes it — with the WRITE doors refusing wide cells and
 * guarded conditions; reads keep the richness. {@link #of(Relation, Tuple2)}
 * and {@link #tuple()} cross the boundary to the tabling cell's pair
 * vocabulary, where the relation is the cell's context.
 */
@Value(staticConstructor = "of")
public class Answer {
	Relation relation;
	Reified<?> reified;
	Condition condition;

	public static Answer of(Relation relation, Tuple2<Reified<?>, Condition> entry) {
		return new Answer(relation, entry._1, entry._2);
	}

	public Tuple2<Reified<?>, Condition> tuple() {
		return Tuple.of(reified, condition);
	}

	/** The row's raw values, positional; the row must be fully ground. */
	public List<Object> values() {
		return Answers.values(reified);
	}

	/** The named column's value — empty for an unknown column or a wide cell. */
	@SuppressWarnings("unchecked")
	public <T> Optional<T> get(Property<T> property) {
		return relation.indexOf(property)
				.map(i -> Answers.positions(reified).get(i))
				.filter(cell -> cell.isVal())
				.flatMap(cell -> Try.of(() -> (T) ((Term<Object>) cell).get())
						.toJavaOptional());
	}

	/**
	 * The guard dropped: asserts UNCONDITIONALLY what was derived under a
	 * condition — a deliberate strengthening of the claim, owned entirely
	 * by the caller, never performed silently. The explicit bridge from a
	 * caveated read to the strict write doors.
	 */
	public Answer unconditional() {
		return new Answer(relation, reified, Condition.ONE);
	}
}
