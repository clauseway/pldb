package org.clauseway.pldb.relations.literals;

// ABOUTME: A literal over an external relation with a column projected away: it reads as the generated
// ABOUTME: projection rule, keeps the write face's type, and refuses to be a fact — a projected row is a question.

import org.clauseway.logic.goals.Goal;
import org.clauseway.logic.solving.Answer;
import org.clauseway.logic.unification.terms.Unifiable;
import org.clauseway.pldb.relations.Relation;
import org.clauseway.pldb.relations.Schema;

import org.clauseway.vavr.collection.Array;

final class ProjectedExternal<R extends Schema<?>> extends RuleLiteral<R> implements External<R> {

	ProjectedExternal(Relation rel, Array<Unifiable<?>> args, R row, Goal full) {
		super(rel, args, row, full);
	}

	@Override
	public Answer<Relation> fact() {
		throw new IllegalStateException("fact() over " + getRelation().getName()
				+ ": a projected column is not stored — a projected row is a question, not a fact");
	}
}
