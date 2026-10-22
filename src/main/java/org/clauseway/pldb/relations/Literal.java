package org.clauseway.pldb.relations;

// ABOUTME: A relation applied to arguments — ONE public type, with how it reads
// ABOUTME: (source, producer, or rule) as a polymorphic Reading behind it.

import org.clauseway.logic.solving.Answer;
import static org.clauseway.logic.unification.terms.LVal.lval;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.ToString;
import lombok.Value;
import lombok.experimental.NonFinal;
import org.clauseway.functional.Nothing;
import org.clauseway.functional.fibers.Cont;
import org.clauseway.functional.fibers.Fiber;
import org.clauseway.functional.tuples.Tuple;
import org.clauseway.logic.constraints.Postable;
import org.clauseway.logic.constraints.Posting;
import org.clauseway.logic.goals.Goal;
import org.clauseway.logic.goals.Knowledge;
import org.clauseway.logic.goals.optimizer.Bounded;
import org.clauseway.logic.tabling.Tabling;
import org.clauseway.logic.solving.Query;
import org.clauseway.logic.solving.Residues;
import org.clauseway.logic.solving.Row;
import org.clauseway.logic.solving.Call;
import org.clauseway.logic.unification.MiniKanren;
import org.clauseway.logic.unification.Substitutions;
import org.clauseway.logic.unification.terms.Reified;
import org.clauseway.logic.unification.terms.Term;
import org.clauseway.logic.unification.structures.LOption;
import org.clauseway.logic.unification.terms.Unifiable;
import org.clauseway.pldb.AnswerProducer;
import org.clauseway.pldb.AnswerSource;
import org.clauseway.pldb.GoalProducer;
import org.clauseway.pldb.constraints.TableConstraints;
import org.clauseway.vavr.collection.Array;

/**
 * A relation applied to arguments. ONE user-facing type: how the relation
 * answers — an enumerating source, a streaming producer, or a rule tabled
 * in the solve — is a {@code Reading} chosen by the builder's terminal and
 * never visible above it. Bare in a conjunction the literal reads its
 * relation; under {@code exclude} (or {@link #posted()}) it imposes; the
 * probe IS the call key, minted at the one reification site
 * ({@link Residues#about}); delivery forks per condition conjunct, each
 * restated at the query anchor. Relation identity is by value (name plus
 * columns): every mint of one definition is the same relation, so a rule
 * literal's recursion is the method calling itself — an ordinary tabled
 * consumer whose rings seal.
 */
@Value
@RequiredArgsConstructor(access = AccessLevel.MODULE)
public class Literal implements Goal, Bounded, Postable {
	Relation rel;
	Array<Unifiable<?>> args;
	Reading reading;
	/** The row this literal was built from, when built from a shape: what {@link #select} rebuilds answers into. Not part of the literal's identity. */
	@EqualsAndHashCode.Exclude
	@ToString.Exclude
	@Getter(AccessLevel.NONE)
	Shape<?> shape;

	public static Literal of(AnswerSource source, Relation rel, List<Unifiable<?>> args) {
		return new Literal(rel, Array.ofAll(args), new SourceReading(source), null);
	}

	public static Literal of(AnswerProducer producer, Relation rel, List<Unifiable<?>> args) {
		return new Literal(rel, Array.ofAll(args), new ProducerReading(producer), null);
	}

	/**
	 * The one internal seam: how a literal answers. Three implementations,
	 * one per builder terminal; the public type never branches on them.
	 */
	interface Reading {
		Goal read(Literal lit);

		Posting posted(Literal lit);

		long estimate(Literal lit, Call<Relation> call);
	}

	/** The sync kind: the source's pairs enumerated inline. */
	@RequiredArgsConstructor(access = AccessLevel.MODULE)
	static final class SourceReading implements Reading {
		private final AnswerSource source;

		@Override
		public Goal read(Literal lit) {
			return lit.lookup((probe, anchor) ->
					StreamSupport.stream(source.answers(probe).spliterator(), false)
							.flatMap(answer -> answer.getCondition().conjuncts().toJavaStream()
									.map(conjunct -> (Goal) Residues.restate(answer.getReified(), conjunct, anchor)))
							.reduce(Goal::or)
							.orElseGet(Goal::failure));
		}

		@Override
		public Posting posted(Literal lit) {
			return TableConstraints.posted(source, lit.rel, lit.args.toJavaList());
		}

		@Override
		public long estimate(Literal lit, Call<Relation> call) {
			return source.estimate(call);
		}
	}

	/** The async kind: emissions deliver as the cell grows, the seal ends the branch. */
	@RequiredArgsConstructor(access = AccessLevel.MODULE)
	static final class ProducerReading implements Reading {
		private final AnswerProducer producer;

		@Override
		public Goal read(Literal lit) {
			return lit.lookup((probe, anchor) -> st -> k -> producer.produce(probe,
					answer -> deliver(answer, anchor).apply(st).apply(k)));
		}

		@Override
		public Posting posted(Literal lit) {
			return TableConstraints.posted(producer, lit.rel, lit.args.toJavaList());
		}

		@Override
		public long estimate(Literal lit, Call<Relation> call) {
			return producer.estimate(call);
		}
	}

	/**
	 * The rule kind: the goal reading is an ordinary tabled call in the
	 * SOLVE's table (recursion seals); the imposition reads the extension
	 * through a {@link GoalProducer} over a per-posting private table — the
	 * memo and the world it memoizes share one closure.
	 */
	@RequiredArgsConstructor(access = AccessLevel.MODULE)
	static final class RuleReading implements Reading {
		private final Goal body;

		@Override
		public Goal read(Literal lit) {
			return Tabling.call(lit.rel,
					Tuple.ofAll(lit.args.map(Unifiable::getObjectUnifiable).toJavaArray()), () -> body);
		}

		@Override
		public Posting posted(Literal lit) {
			return TableConstraints.postedRule(lit.rel, body, lit.args.toJavaList());
		}

		@Override
		public long estimate(Literal lit, Call<Relation> call) {
			return Long.MAX_VALUE;
		}
	}

	/**
	 * The one door, namespaced: the class joins the relation's IDENTITY —
	 * two authors' vocabularies cannot collide in tabling, knowledge
	 * identity, or the store — while the physical name stays the bare
	 * string: tables, endpoints, and mark rows are the backend's world
	 * and spell the name alone.
	 */
	public static Builder relation(Class<?> namespace, String name) {
		return new Builder(namespace.getName(), name);
	}

	/**
	 * The function-shaped front door: columns first, backend last. One
	 * {@link Builder#arg} per column — names stated once, arity unbounded,
	 * the defining method's signature the only typed surface — with
	 * {@link Builder#indexed()}/{@link Builder#ground()} as TAIL modifiers
	 * on the last column, freely composed. The terminal picks the reading:
	 * {@link Builder#from} enumerates a source, {@link Builder#produced}
	 * streams a producer, {@link Builder#solving} tables a rule in the
	 * solve — and a half-built literal is not a goal, so applying one is
	 * unrepresentable.
	 */
	@Value
	public static class Builder {
		String namespace;
		String name;
		List<Property<?>> columns = new ArrayList<>();
		List<Unifiable<?>> values = new ArrayList<>();
		List<Boolean> projectedHere = new ArrayList<>();
		@NonFinal
		@Getter(AccessLevel.NONE)
		Shape<?> shape;

		private Builder(String namespace, String name) {
			this.namespace = namespace;
			this.name = name;
		}

		public Builder arg(String column, Unifiable<?> value) {
			return column(Property.of(column), value);
		}

		/**
		 * The row's columns, its names over its members in order, flags as the
		 * shape declares them; the literal remembers the row for {@link Literal#select}.
		 */
		public <R extends Shape<R>> Builder columns(R row) {
			Iterator<? extends Term<?>> members = row.members().iterator();
			for (Property<?> column : row.columns()) {
				if (!members.hasNext()) {
					throw new IllegalStateException("relation '" + name + "': the shape names column '"
							+ column.getName() + "' but has no member for it");
				}
				Term<?> member = members.next();
				if (!(member instanceof Unifiable)) {
					throw new IllegalStateException("relation '" + name + "': column '" + column.getName()
							+ "' holds a reified term — a literal takes unifiables");
				}
				column(column, (Unifiable<?>) member);
			}
			if (members.hasNext()) {
				throw new IllegalStateException("relation '" + name + "': the shape has more members than columns");
			}
			shape = row;
			return this;
		}

		private Builder column(Property<?> column, Unifiable<?> value) {
			columns.add(column);
			values.add(value);
			projectedHere.add(marker(value).map(Projected::claim).orElse(false));
			return this;
		}

		/** The projection marker at the arg, or one level inside a some(): present, don't care. */
		private static Optional<Projected<?>> marker(Unifiable<?> value) {
			if (value instanceof Projected) {
				return Optional.of((Projected<?>) value);
			}
			return value.asVal()
					.flatMap(MiniKanren::asLOption)
					.filter(option -> !option.isNone())
					.map(LOption::getValue)
					.filter(Projected.class::isInstance)
					.map(inner -> (Projected<?>) inner);
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
				throw new IllegalStateException(
						"relation '" + name + "': a modifier needs a column — declare arg() first");
			}
			columns.set(columns.size() - 1, flag.apply(columns.get(columns.size() - 1)));
			return this;
		}

		private Relation relation() {
			return RelationN.of(namespace, name, columns.toArray(new Property<?>[0]));
		}

		public Literal from(AnswerSource source) {
			return projecting(new Literal(relation(), Array.ofAll(values), new SourceReading(source), shape));
		}

		public Literal produced(AnswerProducer producer) {
			return projecting(new Literal(relation(), Array.ofAll(values), new ProducerReading(producer), shape));
		}

		public Literal solving(Goal body) {
			return projecting(new Literal(relation(), Array.ofAll(values), new RuleReading(body), shape));
		}

		/**
		 * A {@link Projected} column drops out of the head into a GENERATED
		 * projection rule over the full literal — the tabled cell folds
		 * duplicates (∃-projection is set semantics), the posted form makes
		 * {@code exclude} an honest ¬∃, and inside the body the marker is an
		 * ordinary fresh variable. A projected column marked ground()
		 * refuses: an input cannot be projected away.
		 */
		private Literal projecting(Literal full) {
			List<Property<?>> keptColumns = new ArrayList<>();
			List<Unifiable<?>> keptValues = new ArrayList<>();
			StringBuilder keptNames = new StringBuilder();
			for (int i = 0; i < values.size(); i++) {
				if (projectedHere.get(i)) {
					if (columns.get(i).isGround()) {
						throw new IllegalStateException("relation '" + name + "': column '"
								+ columns.get(i).getName() + "' is ground — an input cannot be projected away");
					}
					continue;
				}
				keptColumns.add(columns.get(i));
				keptValues.add(values.get(i));
				keptNames.append(keptNames.length() == 0 ? "" : ",").append(columns.get(i).getName());
			}
			if (keptColumns.size() == values.size()) {
				return full;
			}
			return new Literal(
					RelationN.of(namespace, name + "[" + keptNames + "]", keptColumns.toArray(new Property<?>[0])),
					Array.ofAll(keptValues),
					new RuleReading(full),
					full.shape);
		}
	}

	/** The imposition reading — under {@code exclude} this is the only one. */
	@Override
	public Posting posted() {
		return reading.posted(this);
	}

	/**
	 * The answers as ground rows of the shape this literal was built from:
	 * its members that are variables are selected, and each answer rebuilds
	 * the row with them bound. Refuses a literal built by args rather than
	 * from a shape, a shape of another kind, and a wide cell — a row is
	 * ground, so a projected member, which never binds, cannot be selected.
	 */
	public <R extends Shape<R>> Stream<R> select(Class<R> kind) {
		if (shape == null) {
			throw new IllegalStateException(rel.getName()
					+ ": select needs a literal built from a shape — columns(row), not arg()");
		}
		if (!kind.isInstance(shape)) {
			throw new IllegalStateException(rel.getName() + ": built from a " + shape.getClass().getSimpleName()
					+ ", not a " + kind.getSimpleName());
		}
		R row = kind.cast(shape);
		List<Term<?>> members = StreamSupport.stream(row.members().spliterator(), false)
				.collect(Collectors.<Term<?>> toList());
		Unifiable<?>[] holes = members.stream()
				.filter(member -> !member.isVal())
				.map(member -> (Unifiable<?>) member)
				.toArray(Unifiable<?>[]::new);
		if (holes.length == 0) {
			return Query.of(this).solve(lval(true)).map(answer -> row);
		}
		return Query.of(this).select(holes[0], Arrays.copyOfRange(holes, 1, holes.length)).rows()
				.map(answer -> row.withMembers(members.stream()
						.map(member -> member.isVal() ? member : bound(answer, member))
						.toArray(Term<?>[]::new)));
	}

	/** The answer's cell at a member, which must be ground: a row is a value, not a question. */
	private Term<?> bound(Row answer, Term<?> member) {
		Reified<?> cell = answer.get(member);
		if (!cell.isGround()) {
			throw new IllegalStateException(rel.getName() + ": a wide cell at " + member
					+ " — select answers ground rows only");
		}
		return cell;
	}

	/**
	 * The literal as a stored row: every column must be a ground value.
	 * Refuses loudly by relation and column name — a fact with a hole is a
	 * question, not knowledge.
	 */
	public Answer<Relation> fact() {
		Array<Object> values = args.zipWithIndex().map(t -> {
			if (!t._1.isVal()) {
				throw new IllegalStateException("fact() over " + rel.getName()
						+ ": column '" + rel.getArgs()[t._2].getName() + "' is unbound");
			}
			return (Object) t._1.get();
		});
		return Answers.answer(rel, values.toJavaList());
	}

	@Override
	public Cont<Knowledge, Nothing> apply(Knowledge s) {
		requireGroundColumns(s);
		return reading.read(this).apply(s);
	}

	/** A ground-marked column is an input: free at application is a caller error. */
	private void requireGroundColumns(Knowledge s) {
		Property<?>[] cols = rel.getArgs();
		for (int i = 0; i < cols.length; i++) {
			if (cols[i].isGround() && !((Term<?>) s.walk(args.get(i))).isVal()) {
				throw new IllegalStateException(rel.getName()
						+ ": ground column '" + cols[i].getName() + "' is unbound at application");
			}
		}
	}

	/** Mint the probe at apply, then hand it with its anchor to the reading. */
	private Goal lookup(BiFunction<Call<Relation>, Unifiable<?>, Goal> dispatcher) {
		return s -> Cont.defer(() -> substituteQueryItems(s.substitution(), args)
				.flatMap(q -> {
					Unifiable<?> anchor = lval(Tuple.ofAll(q.map(Unifiable::getObjectTerm).toJavaArray()));
					return Residues.about(s, anchor)
							.map(key -> dispatcher.apply(Call.of(rel, key._1, key._2), anchor).apply(s));
				}));
	}

	/** One answer, forked per condition conjunct, each restated at the anchor. */
	private static Goal deliver(Answer<Relation> answer, Unifiable<?> anchor) {
		return answer.getCondition().conjuncts().toJavaStream()
				.map(conjunct -> (Goal) Residues.restate(answer.getReified(), conjunct, anchor))
				.reduce(Goal::or)
				.orElseGet(Goal::failure);
	}

	@Override
	public long answers(Substitutions s) {
		Reified<?> image = MiniKanren.reify(s,
						lval(Tuple.ofAll(args.map(u -> ((Unifiable<?>) s.walk(u)).getObjectTerm()).toJavaArray()))
								.getObjectTerm())
				.ground();
		return reading.estimate(this, Call.of(rel, image));
	}

	private static Fiber<Array<Unifiable<?>>> substituteQueryItems(Substitutions s, Array<Unifiable<?>> query) {
		return query.toJavaStream()
				.map(u -> (Unifiable<?>) s.walk(u))
				.map(Stream::of)
				.map(Fiber::done)
				.reduce((acc, c) -> Fiber.zip(acc, c).map(t -> t.apply(Stream::concat)))
				.orElseGet(() -> Fiber.done(Stream.empty()))
				.map(q -> q.collect(Array.collector()));
	}

	@Override
	public String toString() {
		return rel.toString() + args;
	}
}
