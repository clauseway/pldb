package org.clauseway.pldb.relations;

// ABOUTME: A relation applied to a row — ONE public type, typed by the row's schema, with how it reads
// ABOUTME: (source, producer, or rule) as a polymorphic Reading behind it; it selects its answers back as rows.

import static org.clauseway.logic.goals.Goal.defer;
import static org.clauseway.logic.nogoods.Exclusion.exclude;
import static org.clauseway.logic.unification.structures.LOption.none;
import static org.clauseway.logic.unification.structures.LOption.some;
import static org.clauseway.logic.unification.terms.LVal.lval;
import static org.clauseway.logic.unification.terms.LVar.lvar;
import static org.clauseway.pldb.relations.Projected.projected;

import org.clauseway.logic.solving.Answer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.RequiredArgsConstructor;
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
import org.clauseway.logic.solving.Query;
import org.clauseway.logic.solving.Solution;
import org.clauseway.logic.tabling.Tabling;
import org.clauseway.logic.solving.Residues;
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
 * A relation applied to a row. ONE user-facing type: how the relation
 * answers — an enumerating source, a streaming producer, or a rule tabled
 * in the solve — is a {@code Reading} chosen by the builder's terminal and
 * never visible above it. Bare in a conjunction the literal reads its
 * relation; under {@code exclude} (or {@link #posted()}) it imposes; the
 * probe IS the call key, minted at the one reification site
 * ({@link Residues#about}); delivery forks per condition conjunct, each
 * restated at the query anchor. Relation identity is by value (name plus
 * columns): every mint of one definition is the same relation, so a rule
 * literal's recursion is the method calling itself — an ordinary tabled
 * consumer whose rings seal. The row the literal was built from rides
 * along, outside its identity, and {@link #select} rebuilds each answer
 * into it: a literal answers as rows of its own schema and no other.
 */
@Value
@NonFinal
@RequiredArgsConstructor(access = AccessLevel.MODULE)
public class Literal<R extends Schema<R>> implements Goal, Bounded, Postable {
	Relation rel;
	Array<Unifiable<?>> args;
	@EqualsAndHashCode.Exclude
	R row;
	Reading reading;

	public static Literal<Schema.Anonymous> of(AnswerSource source, Relation rel, List<Unifiable<?>> args) {
		return new Literal<>(rel, Array.ofAll(args), anonymous(rel, args), new SourceReading(source));
	}

	public static Literal<Schema.Anonymous> of(AnswerProducer producer, Relation rel, List<Unifiable<?>> args) {
		return new Literal<>(rel, Array.ofAll(args), anonymous(rel, args), new ProducerReading(producer));
	}

	/** The relation's columns over the arguments: the row a literal minted from a relation value keeps. */
	private static Schema.Anonymous anonymous(Relation rel, List<Unifiable<?>> args) {
		return new Schema.Anonymous(Arrays.asList(rel.getArgs()), new ArrayList<>(args));
	}

	/**
	 * The one internal seam: how a literal answers. Three implementations,
	 * one per builder terminal; the public type never branches on them.
	 */
	interface Reading {
		Goal read(Literal<?> lit);

		Posting posted(Literal<?> lit);

		long estimate(Literal<?> lit, Call<Relation> call);
	}

	/** The sync kind: the source's pairs enumerated inline. */
	@RequiredArgsConstructor(access = AccessLevel.MODULE)
	static final class SourceReading implements Reading {
		private final AnswerSource source;

		@Override
		public Goal read(Literal<?> lit) {
			return lit.lookup((probe, anchor) ->
					StreamSupport.stream(source.answers(probe).spliterator(), false)
							.flatMap(answer -> answer.getCondition().conjuncts().toJavaStream()
									.map(conjunct -> (Goal) Residues.restate(answer.getReified(), conjunct, anchor)))
							.reduce(Goal::or)
							.orElseGet(Goal::failure));
		}

		@Override
		public Posting posted(Literal<?> lit) {
			return TableConstraints.posted(source, lit.rel, lit.args.toJavaList());
		}

		@Override
		public long estimate(Literal<?> lit, Call<Relation> call) {
			return source.estimate(call);
		}
	}

	/** The async kind: emissions deliver as the cell grows, the seal ends the branch. */
	@RequiredArgsConstructor(access = AccessLevel.MODULE)
	static final class ProducerReading implements Reading {
		private final AnswerProducer producer;

		@Override
		public Goal read(Literal<?> lit) {
			return lit.lookup((probe, anchor) -> st -> k -> producer.produce(probe,
					answer -> deliver(answer, anchor).apply(st).apply(k)));
		}

		@Override
		public Posting posted(Literal<?> lit) {
			return TableConstraints.posted(producer, lit.rel, lit.args.toJavaList());
		}

		@Override
		public long estimate(Literal<?> lit, Call<Relation> call) {
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
		public Goal read(Literal<?> lit) {
			return Tabling.call(lit.rel,
					Tuple.ofAll(lit.args.map(Unifiable::getObjectUnifiable).toJavaArray()), () -> body);
		}

		@Override
		public Posting posted(Literal<?> lit) {
			return TableConstraints.postedRule(lit.rel, body, lit.args.toJavaList());
		}

		@Override
		public long estimate(Literal<?> lit, Call<Relation> call) {
			return Long.MAX_VALUE;
		}
	}

	/**
	 * The one door, namespaced: the class joins the relation's IDENTITY —
	 * two authors' vocabularies cannot collide in tabling, knowledge
	 * identity, or the store — while the physical name stays the bare
	 * string: tables, endpoints, and mark rows are the backend's world
	 * and spell the name alone. The row starts empty; {@link Builder#args}
	 * sets it.
	 */
	public static Builder<Schema.Anonymous> relation(Class<?> namespace, String name) {
		return new Builder<>(namespace.getName(), name,
				new Schema.Anonymous(Collections.emptyList(), Collections.emptyList()));
	}

	/**
	 * A literal reified to a boolean column: true where it answers, false where
	 * it does not. The false branch is an exclusion, so a column the literal does
	 * not care about must be {@code projected()}, never a free {@code lvar()}.
	 */
	public static Goal flag(Literal<?> rule, Unifiable<Boolean> column) {
		return rule.and(column.unifies(true)).or(exclude(rule).and(column.unifies(false)));
	}

	/**
	 * A literal with one hole reified to an option column: {@code some(value)}
	 * for each value the literal answers, {@code none()} where it answers
	 * nothing. The function is applied twice, once with the value and once with
	 * the hole projected away, so both branches are over the same literal.
	 */
	public static <T> Goal option(Function<Unifiable<T>, ? extends Literal<?>> rule, Unifiable<LOption<T>> column) {
		return defer(() -> {
			Unifiable<T> value = lvar();
			return rule.apply(value).and(column.unifies(some(value)));
		}).or(exclude(rule.apply(projected())).and(column.unifies(none())));
	}

	/**
	 * The front door: relation first, row next, backend last. The row's
	 * columns name the head over the row's members, flags as the row
	 * declares them, and the terminal picks the reading: {@link #from}
	 * enumerates a source, {@link #produced} streams a producer,
	 * {@link #solving} tables a rule in the solve. A half-built literal is
	 * not a goal, so applying one is unrepresentable.
	 */
	public static final class Builder<R extends Schema<R>> {
		private final String namespace;
		private final String name;
		private final R row;
		private final List<Property<?>> columns = new ArrayList<>();
		private final List<Unifiable<?>> values = new ArrayList<>();
		private final List<Boolean> projectedHere = new ArrayList<>();

		/**
		 * The row is read, and its markers claimed, HERE rather than at the
		 * terminal: the row is handed over before the body expression is
		 * built, so the head owns a projection and the body sees the marker
		 * as the one join variable it delegates to.
		 */
		private Builder(String namespace, String name, R row) {
			this.namespace = namespace;
			this.name = name;
			this.row = row;
			Iterator<? extends Term<?>> members = row.members().iterator();
			for (Property<?> column : row.columns()) {
				if (!members.hasNext()) {
					throw new IllegalStateException("relation '" + name + "': the row names column '"
							+ column.getName() + "' but has no member for it");
				}
				Term<?> member = members.next();
				if (!(member instanceof Unifiable)) {
					throw new IllegalStateException("relation '" + name + "': column '" + column.getName()
							+ "' holds a reified term — a literal takes unifiables");
				}
				columns.add(column);
				values.add((Unifiable<?>) member);
				projectedHere.add(marker((Unifiable<?>) member).map(Projected::claim).orElse(false));
			}
			if (members.hasNext()) {
				throw new IllegalStateException("relation '" + name + "': the row has more members than columns");
			}
		}

		/** The row the literal is over: its columns are the head, its members the arguments. */
		public <S extends Schema<S>> Builder<S> args(S row) {
			return new Builder<>(namespace, name, row);
		}

		public Literal<R> from(AnswerSource source) {
			return build(new SourceReading(source));
		}

		public Literal<R> produced(AnswerProducer producer) {
			return build(new ProducerReading(producer));
		}

		public Literal<R> solving(Goal body) {
			return build(new RuleReading(body));
		}

		private Literal<R> build(Reading reading) {
			Literal<R> full = new Literal<>(RelationN.of(namespace, name, columns.toArray(new Property<?>[0])),
					Array.ofAll(values), row, reading);
			return projecting(full);
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

		/**
		 * A {@link Projected} column drops out of the head into a GENERATED
		 * projection rule over the full literal — the tabled cell folds
		 * duplicates (∃-projection is set semantics), the posted form makes
		 * {@code exclude} an honest ¬∃, and inside the body the marker is an
		 * ordinary fresh variable. A projected column marked ground()
		 * refuses: an input cannot be projected away.
		 */
		private Literal<R> projecting(Literal<R> full) {
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
			return new Literal<>(
					RelationN.of(namespace, name + "[" + keptNames + "]", keptColumns.toArray(new Property<?>[0])),
					Array.ofAll(keptValues),
					row,
					new RuleReading(full));
		}
	}

	/**
	 * The answers as ground rows of the schema: the row's members that are
	 * variables are selected, and each answer rebuilds the row with them
	 * bound. Refuses a wide cell — a row is ground, so a projected member,
	 * which never binds, cannot be selected.
	 */
	public Stream<R> select() {
		List<Term<?>> members = StreamSupport.stream(row.members().spliterator(), false)
				.collect(Collectors.<Term<?>> toList());
		Unifiable<?>[] holes = members.stream()
				.filter(member -> !member.isVal())
				.map(member -> (Unifiable<?>) member)
				.toArray(Unifiable<?>[]::new);
		if (holes.length == 0) {
			return Query.of(this).solve(lval(true)).map(answer -> row);
		}
		return Query.of(this).select(holes[0], Arrays.copyOfRange(holes, 1, holes.length)).solutions()
				.map(answer -> row.withMembers(members.stream()
						.map(member -> member.isVal() ? member : bound(answer, member))
						.toArray(Term<?>[]::new)));
	}

	/** The answer's cell at a member, which must be ground: a row is a value, not a question. */
	private Term<?> bound(Solution answer, Term<?> member) {
		Reified<?> cell = answer.get(member);
		if (!cell.isGround()) {
			throw new IllegalStateException(rel.getName() + ": a wide cell at " + member
					+ " — select answers ground rows only");
		}
		return cell;
	}

	/** The imposition reading — under {@code exclude} this is the only one. */
	@Override
	public Posting posted() {
		return reading.posted(this);
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
