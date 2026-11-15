package org.clauseway.pldb.relations.literals;

// ABOUTME: A literal over an external relation with a column projected away: it reads as the generated
// ABOUTME: projection rule, prices as the full literal, keeps the write face's type, and refuses to be a fact.

import org.clauseway.logic.solving.Answer;
import org.clauseway.logic.unification.Substitutions;
import org.clauseway.logic.unification.terms.Unifiable;
import org.clauseway.pldb.relations.Relation;
import org.clauseway.pldb.relations.Schema;

import org.clauseway.vavr.collection.Array;

final class ProjectedExternal<R extends Schema<?>> extends RuleLiteral<R> implements External<R> {

	private final External<R> full;

	ProjectedExternal(Relation rel, Array<Unifiable<?>> args, R row, External<R> full) {
		super(rel, args, row, full);
		this.full = full;
	}

	/**
	 * The projection's body is the one literal it projects, so the planner
	 * prices it by that literal's probe, the marked columns free — not at the
	 * maximum an opaque rule body earns.
	 */
	@Override
	public long answers(Substitutions s) {
		return full.answers(s);
	}

	@Override
	public Answer<Relation> fact() {
		throw new IllegalStateException("fact() over " + getRelation().getName()
				+ ": a projected column is not stored — a projected row is a question, not a fact");
	}
}
