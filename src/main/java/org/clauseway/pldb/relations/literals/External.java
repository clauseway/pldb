package org.clauseway.pldb.relations.literals;

// ABOUTME: The write face of a literal: one over an external relation, a relation a backend holds, which a
// ABOUTME: transaction can assert and retract. A rule literal is not one, so a rule cannot be written, by type.

import java.util.Arrays;
import java.util.stream.Collectors;
import org.clauseway.logic.solving.Answer;
import org.clauseway.logic.solving.Selector;
import org.clauseway.logic.solving.Row;
import org.clauseway.logic.unification.terms.Reified;
import org.clauseway.logic.unification.terms.Unifiable;
import org.clauseway.pldb.relations.Property;
import org.clauseway.pldb.relations.Relation;
import org.clauseway.pldb.relations.Schema;
import org.clauseway.pldb.relations.Answers;

import org.clauseway.vavr.collection.Array;

/**
 * A literal over a relation whose rows come from outside the engine: the {@code from} and {@code
 * produced} terminals mint one, the {@code solving} terminal does not.
 * The write doors take this type, so what can be asserted is decided by
 * the compiler: a rule has no row to store.
 */
public interface External<R extends Schema<?>> extends Literal<R> {

	/**
	 * The literal as a stored row: every column must be a ground value.
	 * Refuses loudly by relation and column name — a fact with a hole is a
	 * question, not knowledge.
	 */
	default Answer<Relation> fact() {
		Relation rel = getRelation();
		Array<Object> values = getArgs().zipWithIndex().map(t -> {
			if (!t._1.isVal()) {
				throw new IllegalStateException("fact() over " + rel.getName()
						+ ": column '" + rel.getProperties()[t._2].getName() + "' is unbound");
			}
			return t._1.get();
		});
		return Answers.answer(rel, values.toJavaList());
	}

	/**
	 * The literal as the reading of a question's solutions into its facts: a
	 * {@link Selector} over the literal's columns, mapping every row to
	 * one row of the relation under the row's condition. A constant
	 * column rides into each fact; a column the row leaves free is
	 * refused with the row rendered whole. A {@code projected()} column has
	 * left the relation, so a literal with one has no facts to land.
	 */
	default Selector<Answer<Relation>> facts() {
		int position = 0;
		for (Unifiable<?> key : getRow().keys()) {
			if (Projection.marker(key).isPresent()) {
				throw new IllegalStateException("column '" + getRow().columns().get(position).getName()
						+ "' is projected() — a projection leaves the relation; a literal landing facts"
						+ " needs a variable or a value in every column");
			}
			position++;
		}
		Relation rel = getRelation();
		Array<Unifiable<?>> args = getArgs();
		return new Selector<Answer<Relation>>() {
			@Override
			public Iterable<? extends Unifiable<?>> keys() {
				return args;
			}

			@Override
			public Iterable<String> names() {
				return Arrays.stream(rel.getProperties()).map(Property::getName).collect(Collectors.toList());
			}

			@Override
			public Answer<Relation> map(Row row) {
				row.ground();
				return Answer.of(rel,
						Answers.image(args.<Reified<?>> map(row::cell).toJavaList()),
						row.getCondition());
			}
		};
	}
}
