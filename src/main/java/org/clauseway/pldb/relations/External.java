package org.clauseway.pldb.relations;

// ABOUTME: The write face of a literal: one over an external relation, a relation a backend holds, which a
// ABOUTME: transaction can assert and retract. A rule literal is not one, so a rule cannot be written, by type.

import org.clauseway.logic.solving.Answer;
import org.clauseway.vavr.collection.Array;

/**
 * A literal over an external relation: the {@code from} and {@code
 * produced} terminals mint one, the {@code solving} terminal does not.
 * The write doors take this type, so what can be asserted is decided by
 * the compiler: a rule has no row to store.
 */
public interface External<R extends Schema<R>> extends Literal<R> {

	/**
	 * The literal as a stored row: every column must be a ground value.
	 * Refuses loudly by relation and column name — a fact with a hole is a
	 * question, not knowledge.
	 */
	default Answer<Relation> fact() {
		Relation rel = getRel();
		Array<Object> values = getArgs().zipWithIndex().map(t -> {
			if (!t._1.isVal()) {
				throw new IllegalStateException("fact() over " + rel.getName()
						+ ": column '" + rel.getArgs()[t._2].getName() + "' is unbound");
			}
			return (Object) t._1.get();
		});
		return Answers.answer(rel, values.toJavaList());
	}
}
