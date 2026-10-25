package org.clauseway.pldb.relations.literals;

// ABOUTME: The literal's builder as a chain of staged types: the relation named, then its row — typed, or
// ABOUTME: anonymous column by column — then the backend. Each step's type offers only what may follow it.

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import org.clauseway.logic.goals.Goal;
import org.clauseway.logic.unification.terms.Unifiable;
import org.clauseway.pldb.AnswerProducer;
import org.clauseway.pldb.AnswerSource;
import org.clauseway.pldb.relations.Property;
import org.clauseway.pldb.relations.Relation;
import org.clauseway.pldb.relations.RelationN;
import org.clauseway.pldb.relations.Schema;
import org.clauseway.vavr.collection.Array;

/**
 * The front door, one legal step at a time: relation first, row next,
 * backend last. {@link Literal#relation} opens one; this type is the
 * first step, {@link Columns} and {@link Backend} the next. A flag
 * before a column, a column after a typed row, or a literal with no
 * backend is unrepresentable rather than refused.
 */
public interface LiteralBuilder {

	/** The row the literal is over: its columns are the head, its keys the arguments. */
	<T extends Schema<?>> Backend<T> schema(T row);

	/** The first column of an anonymous row, named on the literal itself. */
	Columns arg(String column, Unifiable<?> value);

	/** The relation named, its row not yet given. */
	static LiteralBuilder of(String namespace, String name) {
		return new Named(namespace, name);
	}

	/** An anonymous row on its literal: another column, a flag on the last one named, or the backend. */
	interface Columns extends Backend<Schema.Anonymous> {

		Columns arg(String column, Unifiable<?> value);

		/** Marks the LAST declared column. */
		Columns indexed();

		/** Marks the LAST declared column as an input: free at application is a caller error. */
		Columns ground();
	}

	/**
	 * The last step picks the kind: {@link #from} enumerates a source,
	 * {@link #produced} streams a producer, {@link #solving} tables a rule
	 * in the solve. A half-built literal is not a goal, so applying one is
	 * unrepresentable.
	 */
	interface Backend<S extends Schema<?>> {

		External<S> from(AnswerSource source);

		External<S> produced(AnswerProducer producer);

		Literal<S> solving(Goal body);
	}

	/**
	 * The relation named, its row not yet given. A row's markers are
	 * claimed as the row arrives — the typed row whole, the anonymous one
	 * column by column — and never at the terminal: the row is in hand
	 * before the body expression is built, so the head owns a projection
	 * and the body sees the marker as the one join variable it delegates
	 * to.
	 */
	final class Named implements LiteralBuilder {
		private final String namespace;
		private final String name;

		private Named(String namespace, String name) {
			this.namespace = namespace;
			this.name = name;
		}

		@Override
		public <T extends Schema<?>> Backend<T> schema(T row) {
			List<Boolean> projectedHere = new ArrayList<>();
			for (Unifiable<?> key : row.keys()) {
				projectedHere.add(Projection.claimed(key));
			}
			return new Builder<>(namespace, name, row, projectedHere);
		}

		@Override
		public Columns arg(String column, Unifiable<?> value) {
			return new Inline(namespace, name, column, value);
		}
	}

	/** An anonymous row accumulating on its literal; the terminal builds the row and hands it on. */
	final class Inline implements Columns {
		private final String namespace;
		private final String name;
		private final List<Boolean> projectedHere = new ArrayList<>();
		private Schema.Columns row;

		private Inline(String namespace, String name, String column, Unifiable<?> value) {
			this.namespace = namespace;
			this.name = name;
			this.row = Schema.builder().arg(column, value);
			projectedHere.add(Projection.claimed(value));
		}

		@Override
		public Columns arg(String column, Unifiable<?> value) {
			row = row.arg(column, value);
			projectedHere.add(Projection.claimed(value));
			return this;
		}

		@Override
		public Columns indexed() {
			row = row.indexed();
			return this;
		}

		@Override
		public Columns ground() {
			row = row.ground();
			return this;
		}

		@Override
		public External<Schema.Anonymous> from(AnswerSource source) {
			return built().from(source);
		}

		@Override
		public External<Schema.Anonymous> produced(AnswerProducer producer) {
			return built().produced(producer);
		}

		@Override
		public Literal<Schema.Anonymous> solving(Goal body) {
			return built().solving(body);
		}

		private Builder<Schema.Anonymous> built() {
			return new Builder<>(namespace, name, row.build(), projectedHere);
		}
	}

	/** A row in hand, its markers claimed: the columns zipped over the keys, awaiting the backend. */
	final class Builder<S extends Schema<?>> implements Backend<S> {
		private final String namespace;
		private final String name;
		private final S schema;
		private final List<Property<?>> columns = new ArrayList<>();
		private final List<Unifiable<?>> values = new ArrayList<>();
		private final List<Boolean> projectedHere;

		private Builder(String namespace, String name, S schema, List<Boolean> projectedHere) {
			this.namespace = namespace;
			this.name = name;
			this.schema = schema;
			this.projectedHere = projectedHere;
			Iterator<? extends Unifiable<?>> keys = schema.keys().iterator();
			for (Property<?> column : schema.columns()) {
				if (!keys.hasNext()) {
					throw new IllegalStateException("relation '" + name + "': the schema names column '"
							+ column.getName() + "' but has no key for it");
				}
				columns.add(column);
				values.add(keys.next());
			}
			if (keys.hasNext()) {
				throw new IllegalStateException("relation '" + name + "': the row has more keys than columns");
			}
		}

		@Override
		public External<S> from(AnswerSource source) {
			SourceLiteral<S> full = new SourceLiteral<>(relation(), Array.ofAll(values), schema, source);
			return kept().<External<S>> map(kept -> new ProjectedExternal<>(kept.rel, kept.args, schema, full)).orElse(full);
		}

		@Override
		public External<S> produced(AnswerProducer producer) {
			ProducerLiteral<S> full = new ProducerLiteral<>(relation(), Array.ofAll(values), schema, producer);
			return kept().<External<S>> map(kept -> new ProjectedExternal<>(kept.rel, kept.args, schema, full)).orElse(full);
		}

		@Override
		public Literal<S> solving(Goal body) {
			RuleLiteral<S> full = new RuleLiteral<>(relation(), Array.ofAll(values), schema, body);
			return kept().<Literal<S>> map(kept -> new RuleLiteral<>(kept.rel, kept.args, schema, full)).orElse(full);
		}

		private Relation relation() {
			return RelationN.of(namespace, name, columns.toArray(new Property<?>[0]));
		}

		/** The head without the projected columns, if any member carries a marker. */
		private Optional<Projection.Kept> kept() {
			return Projection.kept(namespace, name, columns, values, projectedHere);
		}
	}
}
