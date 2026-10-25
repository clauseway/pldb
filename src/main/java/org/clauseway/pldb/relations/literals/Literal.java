package org.clauseway.pldb.relations.literals;

// ABOUTME: A relation applied to a row — the ONE public read type, typed by the row's schema: a goal in a
// ABOUTME: conjunction, a constraint under exclude, selected back as rows. Built through relation(ns, name), one legal step at a time.

import static org.clauseway.logic.goals.Goal.defer;
import static org.clauseway.logic.nogoods.Exclusion.exclude;
import static org.clauseway.logic.unification.structures.LOption.none;
import static org.clauseway.logic.unification.structures.LOption.some;
import static org.clauseway.logic.unification.terms.LVal.lval;
import static org.clauseway.logic.unification.terms.LVar.lvar;
import static org.clauseway.pldb.relations.Projected.projected;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import org.clauseway.functional.Nothing;
import org.clauseway.functional.fibers.Cont;
import org.clauseway.functional.tuples.Tuple;
import org.clauseway.logic.constraints.Postable;
import org.clauseway.logic.goals.Goal;
import org.clauseway.logic.goals.Knowledge;
import org.clauseway.logic.goals.optimizer.Bounded;
import org.clauseway.logic.solving.Call;
import org.clauseway.logic.solving.Residues;
import org.clauseway.logic.unification.MiniKanren;
import org.clauseway.logic.unification.Substitutions;
import org.clauseway.logic.unification.structures.LOption;
import org.clauseway.logic.unification.terms.Reified;
import org.clauseway.logic.unification.terms.Term;
import org.clauseway.logic.unification.terms.Unifiable;
import org.clauseway.pldb.AnswerProducer;
import org.clauseway.pldb.AnswerSource;
import org.clauseway.pldb.relations.Property;
import org.clauseway.pldb.relations.Relation;
import org.clauseway.pldb.relations.RelationN;
import org.clauseway.pldb.relations.Schema;
import org.clauseway.vavr.collection.Array;

/**
 * A relation applied to a row. ONE user-facing read type: how the relation
 * answers — an enumerating source, a streaming producer, or a rule tabled
 * in the solve — is the kind the builder's terminal mints, and a reader
 * never branches on it. Bare in a conjunction the literal reads its
 * relation; under {@code exclude} (or {@link #posted()}) it imposes; the
 * probe IS the call key, minted at the one reification site
 * ({@link Residues#about}). Relation identity is by value (name plus
 * columns): every mint of one definition is the same relation, so a rule
 * literal's recursion is the method calling itself — an ordinary tabled
 * consumer whose rings seal. The row the literal was built from rides
 * along, outside its identity, and {@link Question#select(Literal)} rebuilds each
 * answer into it: a literal answers as rows of its own schema and no other. The
 * kinds a transaction can write are {@link External}.
 */
public interface Literal<S extends Schema<?>> extends Goal, Bounded, Postable {

	Relation getRelation();

	Array<Unifiable<?>> getArgs();

	S getRow();

	/** The kind's reading as a goal; {@link #apply} checks the ground columns first. */
	Goal read();

	/** The kind's answer estimate for a probe, for the optimizer. */
	long estimate(Call<Relation> call);

	static External<Schema.Anonymous> of(AnswerSource source, Relation rel, List<Unifiable<?>> args) {
		return new SourceLiteral<>(rel, Array.ofAll(args), anonymous(rel, args), source);
	}

	static External<Schema.Anonymous> of(AnswerProducer producer, Relation rel, List<Unifiable<?>> args) {
		return new ProducerLiteral<>(rel, Array.ofAll(args), anonymous(rel, args), producer);
	}

	/** The relation's columns over the arguments: the row a literal minted from a relation value keeps. */
	static Schema.Anonymous anonymous(Relation rel, List<Unifiable<?>> args) {
		return new Schema.Anonymous(Arrays.asList(rel.getProperties()), new ArrayList<>(args));
	}

	/**
	 * The one door, namespaced: the class joins the relation's IDENTITY —
	 * two authors' vocabularies cannot collide in tabling, knowledge
	 * identity, or the store — while the physical name stays the bare
	 * string: tables, endpoints, and mark rows are the backend's world
	 * and spell the name alone. The row comes next: a typed one through
	 * {@link Head#schema}, or an anonymous one named column by column
	 * through {@link Head#arg}.
	 */
	static Head relation(Class<?> namespace, String name) {
		return new Named(namespace.getName(), name);
	}

	/**
	 * A literal reified to a boolean column: true where it answers, false where
	 * it does not. The false branch is an exclusion, so a column the literal does
	 * not care about must be {@code projected()}, never a free {@code lvar()}.
	 */
	static Goal flag(Literal<?> rule, Unifiable<Boolean> column) {
		return rule.and(column.unifies(true)).or(exclude(rule).and(column.unifies(false)));
	}

	/**
	 * A literal with one hole reified to an option column: {@code some(value)}
	 * for each value the literal answers, {@code none()} where it answers
	 * nothing. The function is applied twice, once with the value and once with
	 * the hole projected away, so both branches are over the same literal.
	 */
	static <T> Goal option(Function<Unifiable<T>, ? extends Literal<?>> rule, Unifiable<LOption<T>> column) {
		return defer(() -> {
			Unifiable<T> value = lvar();
			return rule.apply(value).and(column.unifies(some(value)));
		}).or(exclude(rule.apply(projected())).and(column.unifies(none())));
	}

	/**
	 * The front door, one legal step at a time: relation first, row next,
	 * backend last. Each step's type offers only what may follow it, so a
	 * flag before a column, a column after a typed row, or a literal with
	 * no backend is unrepresentable rather than refused.
	 */
	interface Head {

		/** The row the literal is over: its columns are the head, its keys the arguments. */
		<T extends Schema<?>> Backend<T> schema(T row);

		/** The first column of an anonymous row, named on the literal itself. */
		Columns arg(String column, Unifiable<?> value);
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
	final class Named implements Head {
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

	@Override
	default Cont<Knowledge, Nothing> apply(Knowledge s) {
		requireGroundColumns(s);
		return read().apply(s);
	}

	/** A ground-marked column is an input: free at application is a caller error. */
	default void requireGroundColumns(Knowledge s) {
		Property<?>[] cols = getRelation().getProperties();
		Array<Unifiable<?>> args = getArgs();
		for (int i = 0; i < cols.length; i++) {
			if (cols[i].isGround() && !((Term<?>) s.walk(args.get(i))).isVal()) {
				throw new IllegalStateException(getRelation().getName()
						+ ": ground column '" + cols[i].getName() + "' is unbound at application");
			}
		}
	}

	@Override
	default long answers(Substitutions s) {
		Reified<?> image = MiniKanren.reify(s,
						lval(Tuple.ofAll(getArgs().map(u ->
								s.walk(u).getObjectTerm()).toJavaArray()))
								.getObjectTerm())
				.ground();
		return estimate(Call.of(getRelation(), image));
	}
}
