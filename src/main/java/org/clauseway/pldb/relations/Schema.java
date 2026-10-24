package org.clauseway.pldb.relations;

// ABOUTME: A relation schema as a type: the structural contract plus the columns' names and flags, in member
// ABOUTME: order. An instance is a row of it; a literal is built from one, keeps it, and selects its answers back as rows.

import static org.clauseway.pldb.relations.Projected.projected;

import java.util.List;
import org.clauseway.logic.unification.structures.Structural;
import org.clauseway.logic.unification.terms.Term;

/**
 * A schema signs the structural contract and names its members: one
 * {@link Property} per member, in order, carrying the column's name and
 * flags. An instance is a row of the schema — a pattern with variables, a
 * probe, or a ground answer. That is all a relation needs from a type: a
 * literal over a row ({@code relation(ns, name).rows(row)}) and
 * {@link Rows#select} derive from it.
 */
public interface Schema<R extends Schema<R>> extends Structural<R> {

	/** The columns, one per member, in member order. */
	List<Property<?>> columns();

	/** This schema with every column projected away: the row a rule probes, naming only the columns it cares about. */
	default R probe() {
		return withMembers(columns().stream()
				.map(column -> projected())
				.toArray(Term<?>[]::new));
	}
}
