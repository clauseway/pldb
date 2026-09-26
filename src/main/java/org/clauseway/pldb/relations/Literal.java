package org.clauseway.pldb.relations;

// ABOUTME: A relation applied to arguments — ONE public type, with how it reads
// ABOUTME: (source, producer, or rule) as a polymorphic Reading behind it.

import static org.clauseway.logic.unification.terms.LVal.lval;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.Value;
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
import org.clauseway.logic.tabling.conditions.Residues;
import org.clauseway.logic.tabling.table.Call;
import org.clauseway.logic.unification.MiniKanren;
import org.clauseway.logic.unification.Substitutions;
import org.clauseway.logic.unification.terms.Reified;
import org.clauseway.logic.unification.terms.Term;
import org.clauseway.logic.unification.terms.Unifiable;
import org.clauseway.pldb.AnswerProducer;
import org.clauseway.pldb.AnswerSource;
import org.clauseway.pldb.GoalProducer;
import org.clauseway.pldb.constraints.TableConstraints;
import org.clauseway.vavr.collection.Array;

/**
 * A relation applied to arguments. ONE user-facing type: how the relation
 * answers — an enumerating source, a streaming producer, or a rule tabled
 * in the solve — is a {@link Reading} chosen by the builder's terminal and
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

	public static Literal of(AnswerSource source, Relation rel, List<Unifiable<?>> args) {
		return new Literal(rel, Array.ofAll(args), new SourceReading(source));
	}

	public static Literal of(AnswerProducer producer, Relation rel, List<Unifiable<?>> args) {
		return new Literal(rel, Array.ofAll(args), new ProducerReading(producer));
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

	@Value
	public static class Builder {
		String namespace;
		String name;
		List<Property<?>> columns = new ArrayList<>();
		List<Unifiable<?>> values = new ArrayList<>();
		List<Boolean> projectedHere = new ArrayList<>();

		private Builder(String namespace, String name) {
			this.namespace = namespace;
			this.name = name;
		}

		public Builder arg(String column, Unifiable<?> value) {
			columns.add(Property.of(column));
			values.add(value);
			projectedHere.add(value instanceof Projected && ((Projected<?>) value).claim());
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

		/** Marks the LAST declared column nullable. */
		public Builder nullable() {
			return modify(Property::nullable);
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
			return projecting(new Literal(relation(), Array.ofAll(values), new SourceReading(source)));
		}

		public Literal produced(AnswerProducer producer) {
			return projecting(new Literal(relation(), Array.ofAll(values), new ProducerReading(producer)));
		}

		public Literal solving(Goal body) {
			return projecting(new Literal(relation(), Array.ofAll(values), new RuleReading(body)));
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
					new RuleReading(full));
		}
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
	public Answer fact() {
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
	private static Goal deliver(Answer answer, Unifiable<?> anchor) {
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
