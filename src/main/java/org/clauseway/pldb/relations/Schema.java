package org.clauseway.pldb.relations;

// ABOUTME: A relation schema as a type: a selector whose keys are the columns' variables, plus the columns' names
// ABOUTME: and flags in key order. An instance is a row of it; a literal is built from one and selects its values back.

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.UnaryOperator;
import lombok.Value;

import org.clauseway.logic.solving.Selector;
import org.clauseway.logic.solving.Solution;
import org.clauseway.logic.unification.terms.Unifiable;

/**
 * A schema is a {@link Selector} that names its keys: one {@link Property}
 * per key, in order, carrying the column's name and flags. An instance is
 * a row of the schema — a pattern with variables, or a probe — and the
 * value it maps a solution into is the row as Java reads it. A typed row
 * states both in one place: {@link #describe()} names its columns over
 * its fields, and its keys and columns are read off that. That is all a
 * relation needs from a type: a literal over a row
 * ({@code relation(ns, name).args(row)}) zips the columns over the keys,
 * and {@code Query.select(row)} streams the values. A row with no Java
 * type is an {@link Anonymous}, built column by column through
 * {@link #builder()} — each step offers only what is legal next — and
 * maps a solution into itself.
 *
 * @param <D> the value a row of this schema maps a solution into
 */
public interface Schema<D> extends Selector<D> {

	/** This row with its type set aside: its columns named over its keys, in order. */
	Anonymous describe();

	@Override
	default Iterable<? extends Unifiable<?>> keys() {
		return describe().keys();
	}

	/** The columns, one per key, in key order. */
	default List<Property<?>> columns() {
		return describe().columns();
	}

	/** An anonymous row, one column at a time: the first column, then {@link Columns}. */
	static Builder builder() {
		return (column, value) -> new Building().arg(column, value);
	}

	/** The first step of an anonymous row: its first column. No flag is available before a column is named. */
	interface Builder {
		Columns arg(String column, Unifiable<?> value);
	}

	/** An anonymous row with at least one column: another column, a flag on the last one named, or the row. */
	interface Columns extends Builder {

		/** Marks the LAST declared column. */
		Columns indexed();

		/**
		 * Marks the LAST declared column as an input: its argument must walk
		 * to a ground value when the literal applies, refused loudly
		 * otherwise.
		 */
		Columns ground();

		Anonymous build();
	}

	/** A row of no Java type: its columns and its keys, both by position; a solution over it is its own value. */
	@Value
	class Anonymous implements Schema<Solution> {
		List<Property<?>> columns;
		List<Unifiable<?>> keys;

		@Override
		public Anonymous describe() {
			return this;
		}

		@Override
		public Iterable<? extends Unifiable<?>> keys() {
			return keys;
		}

		@Override
		public Solution map(Solution solution) {
			return solution;
		}

		@Override
		public List<Property<?>> columns() {
			return columns;
		}
	}

	/** The anonymous row's columns and keys as they accumulate; only {@link #builder()} opens one. */
	final class Building implements Columns {
		private final List<Property<?>> columns = new ArrayList<>();
		private final List<Unifiable<?>> keys = new ArrayList<>();

		private Building() {
		}

		@Override
		public Columns arg(String column, Unifiable<?> value) {
			columns.add(Property.of(column));
			keys.add(value);
			return this;
		}

		@Override
		public Columns indexed() {
			return modify(Property::indexed);
		}

		@Override
		public Columns ground() {
			return modify(Property::ground);
		}

		private Columns modify(UnaryOperator<Property<?>> flag) {
			columns.set(columns.size() - 1, flag.apply(columns.get(columns.size() - 1)));
			return this;
		}

		@Override
		public Anonymous build() {
			return new Anonymous(Collections.unmodifiableList(new ArrayList<>(columns)),
					Collections.unmodifiableList(new ArrayList<>(keys)));
		}
	}
}
