package org.clauseway.pldb.relations;

// ABOUTME: A relation schema as a type: the structural contract plus the columns' names and flags, in member
// ABOUTME: order. An instance is a row of it; a literal is built from one, keeps it, and selects its answers back as rows.

import static org.clauseway.pldb.relations.Projected.projected;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.UnaryOperator;
import lombok.Value;

import org.clauseway.logic.unification.structures.Structural;
import org.clauseway.logic.unification.terms.Term;
import org.clauseway.logic.unification.terms.Unifiable;

/**
 * A schema signs the structural contract and names its members: one
 * {@link Property} per member, in order, carrying the column's name and
 * flags. An instance is a row of the schema — a pattern with variables, a
 * probe, or a ground answer. That is all a relation needs from a type: a
 * literal over a row ({@code relation(ns, name).args(row)}) and
 * {@code select()} derive from it. A row with no Java type is an
 * {@link Anonymous}, built column by column through {@link #builder()}.
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

	/** An anonymous row, one column at a time. */
	static Builder builder() {
		return new Builder();
	}

	/** A row of no Java type: its columns and its members, both by position. */
	@Value
	class Anonymous implements Schema<Anonymous> {
		List<Property<?>> columns;
		List<Term<?>> members;

		@Override
		public Iterable<? extends Term<?>> members() {
			return members;
		}

		@Override
		public Anonymous withMembers(Term<?>[] members) {
			return new Anonymous(columns, Arrays.asList(members));
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
		private final List<Term<?>> members = new ArrayList<>();

		private Builder() {
		}

		public Builder arg(String column, Unifiable<?> value) {
			columns.add(Property.of(column));
			members.add(value);
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
					Collections.unmodifiableList(new ArrayList<>(members)));
		}
	}
}
