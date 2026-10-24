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
 * value it maps a solution into is the row as Java reads it. That is all a
 * relation needs from a type: a literal over a row
 * ({@code relation(ns, name).args(row)}) zips the columns over the keys,
 * and {@code Query.select(row)} streams the values. A row with no Java
 * type is an {@link Anonymous}, built column by column through
 * {@link #builder()}, and maps a solution into itself.
 *
 * @param <D> the value a row of this schema maps a solution into
 */
public interface Schema<D> extends Selector<D> {

	/** The columns, one per key, in key order. */
	List<Property<?>> columns();

	/** An anonymous row, one column at a time. */
	static Builder builder() {
		return new Builder();
	}

	/** A row of no Java type: its columns and its keys, both by position; a solution over it is its own value. */
	@Value
	class Anonymous implements Schema<Solution> {
		List<Property<?>> columns;
		List<Unifiable<?>> keys;

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

	/**
	 * The anonymous row's builder: one {@link #arg} per column, names stated
	 * once, with {@link #indexed()} and {@link #ground()} as tail modifiers on
	 * the last column named.
	 */
	final class Builder {
		private final List<Property<?>> columns = new ArrayList<>();
		private final List<Unifiable<?>> keys = new ArrayList<>();

		private Builder() {
		}

		public Builder arg(String column, Unifiable<?> value) {
			columns.add(Property.of(column));
			keys.add(value);
			return this;
		}

		/** Marks the LAST declared column; refuses before any column. */
		public Builder indexed() {
			return modify(Property::indexed);
		}

		/**
		 * Marks the LAST declared column as an input: its argument must walk
		 * to a ground value when the literal applies, refused loudly
		 * otherwise.
		 */
		public Builder ground() {
			return modify(Property::ground);
		}

		private Builder modify(UnaryOperator<Property<?>> flag) {
			if (columns.isEmpty()) {
				throw new IllegalStateException("a modifier needs a column — declare arg() first");
			}
			columns.set(columns.size() - 1, flag.apply(columns.get(columns.size() - 1)));
			return this;
		}

		public Anonymous build() {
			return new Anonymous(Collections.unmodifiableList(new ArrayList<>(columns)),
					Collections.unmodifiableList(new ArrayList<>(keys)));
		}
	}
}
