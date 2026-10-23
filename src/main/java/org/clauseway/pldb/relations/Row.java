package org.clauseway.pldb.relations;

// ABOUTME: A row of a relation as a signed value: the structural contract plus the columns' names and flags,
// ABOUTME: in member order. A literal is built from it, remembers it, and selects its answers back as it.

import static org.clauseway.pldb.relations.Projected.projected;

import java.util.List;
import org.clauseway.logic.unification.structures.Structural;
import org.clauseway.logic.unification.terms.Term;

/**
 * A row signs the structural contract and names its members: one
 * {@link Property} per member, in order, carrying the column's name and
 * flags. That is all a relation needs from a row type; a literal over the
 * row ({@code relation(ns, name).columns(row)}), the template, the probe
 * and {@link Literal#select} derive from it.
 */
public interface Row<R extends Row<R>> extends Structural<R> {

	/** The columns, one per member, in member order. */
	List<Property<?>> columns();

	/** This kind with every column projected away: the row a rule probes, naming only the columns it cares about. */
	default R probe() {
		return withMembers(columns().stream()
				.map(column -> projected())
				.toArray(Term<?>[]::new));
	}
}
