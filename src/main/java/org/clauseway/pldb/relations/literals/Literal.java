package org.clauseway.pldb.relations.literals;

// ABOUTME: A relation applied to a row — the ONE public read type, typed by the row's schema: a goal in a
// ABOUTME: conjunction, a constraint under exclude, selected back as rows. Built through relation(ns, name).args(row).

import static org.clauseway.logic.goals.Goal.defer;
import static org.clauseway.logic.nogoods.Exclusion.exclude;
import static org.clauseway.logic.unification.structures.LOption.none;
import static org.clauseway.logic.unification.structures.LOption.some;
import static org.clauseway.logic.unification.terms.LVal.lval;
import static org.clauseway.logic.unification.terms.LVar.lvar;
import static org.clauseway.pldb.relations.Projected.projected;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.clauseway.functional.Nothing;
import org.clauseway.functional.fibers.Cont;
import org.clauseway.functional.tuples.Tuple;
import org.clauseway.logic.constraints.Postable;
import org.clauseway.logic.goals.Goal;
import org.clauseway.logic.goals.Knowledge;
import org.clauseway.logic.goals.optimizer.Bounded;
import org.clauseway.logic.solving.Call;
import org.clauseway.logic.solving.Query;
import org.clauseway.logic.solving.Residues;
import org.clauseway.logic.solving.Solution;
import org.clauseway.logic.unification.MiniKanren;
import org.clauseway.logic.unification.Substitutions;
import org.clauseway.logic.unification.structures.LOption;
import org.clauseway.logic.unification.terms.Reified;
import org.clauseway.logic.unification.terms.Term;
import org.clauseway.logic.unification.terms.Unifiable;
import org.clauseway.pldb.relations.Relation;
import org.clauseway.pldb.relations.RelationN;
import org.clauseway.pldb.relations.Property;
import org.clauseway.pldb.relations.Schema;
import org.clauseway.pldb.relations.Projected;

import org.clauseway.pldb.AnswerProducer;
import org.clauseway.pldb.AnswerSource;
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
 * along, outside its identity, and {@link #select} rebuilds each answer
 * into it: a literal answers as rows of its own schema and no other. The
 * kinds a transaction can write are {@link External}.
 */
public interface Literal<R extends Schema<R>> extends Goal, Bounded, Postable {

	Relation getRel();

	Array<Unifiable<?>> getArgs();

	R getRow();

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
		return new Schema.Anonymous(Arrays.asList(rel.getArgs()), new ArrayList<>(args));
	}

	/**
	 * The one door, namespaced: the class joins the relation's IDENTITY —
	 * two authors' vocabularies cannot collide in tabling, knowledge
	 * identity, or the store — while the physical name stays the bare
	 * string: tables, endpoints, and mark rows are the backend's world
	 * and spell the name alone. The row starts empty; {@link Builder#args}
	 * sets it.
	 */
	static Builder<Schema.Anonymous> relation(Class<?> namespace, String name) {
		return new Builder<>(namespace.getName(), name,
				new Schema.Anonymous(Collections.emptyList(), Collections.emptyList()));
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
	 * The front door: relation first, row next, backend last. The row's
	 * columns name the head over the row's members, flags as the row
	 * declares them, and the terminal picks the kind: {@link #from}
	 * enumerates a source, {@link #produced} streams a producer,
	 * {@link #solving} tables a rule in the solve. A half-built literal is
	 * not a goal, so applying one is unrepresentable.
	 */
	final class Builder<R extends Schema<R>> {
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
				projectedHere.add(Projection.marker((Unifiable<?>) member).map(Projected::claim).orElse(false));
			}
			if (members.hasNext()) {
				throw new IllegalStateException("relation '" + name + "': the row has more members than columns");
			}
		}

		/** The row the literal is over: its columns are the head, its members the arguments. */
		public <S extends Schema<S>> Builder<S> args(S row) {
			return new Builder<>(namespace, name, row);
		}

		public External<R> from(AnswerSource source) {
			SourceLiteral<R> full = new SourceLiteral<>(relation(), Array.ofAll(values), row, source);
			return kept().<External<R>> map(kept -> new ProjectedExternal<>(kept.rel, kept.args, row, full)).orElse(full);
		}

		public External<R> produced(AnswerProducer producer) {
			ProducerLiteral<R> full = new ProducerLiteral<>(relation(), Array.ofAll(values), row, producer);
			return kept().<External<R>> map(kept -> new ProjectedExternal<>(kept.rel, kept.args, row, full)).orElse(full);
		}

		public Literal<R> solving(Goal body) {
			RuleLiteral<R> full = new RuleLiteral<>(relation(), Array.ofAll(values), row, body);
			return kept().<Literal<R>> map(kept -> new RuleLiteral<>(kept.rel, kept.args, row, full)).orElse(full);
		}

		private Relation relation() {
			return RelationN.of(namespace, name, columns.toArray(new Property<?>[0]));
		}

		/** The head without the projected columns, if any member carries a marker. */
		private Optional<Projection.Kept> kept() {
			return Projection.kept(namespace, name, columns, values, projectedHere);
		}
	}

	/**
	 * The answers as ground rows of the schema: the row's members that are
	 * variables are selected, and each answer rebuilds the row with them
	 * bound. Refuses a wide cell — a row is ground, so a projected member,
	 * which never binds, cannot be selected.
	 */
	default Stream<R> select() {
		R row = getRow();
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
	default Term<?> bound(Solution answer, Term<?> member) {
		Reified<?> cell = answer.get(member);
		if (!cell.isGround()) {
			throw new IllegalStateException(getRel().getName() + ": a wide cell at " + member
					+ " — select answers ground rows only");
		}
		return cell;
	}

	@Override
	default Cont<Knowledge, Nothing> apply(Knowledge s) {
		requireGroundColumns(s);
		return read().apply(s);
	}

	/** A ground-marked column is an input: free at application is a caller error. */
	default void requireGroundColumns(Knowledge s) {
		Property<?>[] cols = getRel().getArgs();
		Array<Unifiable<?>> args = getArgs();
		for (int i = 0; i < cols.length; i++) {
			if (cols[i].isGround() && !((Term<?>) s.walk(args.get(i))).isVal()) {
				throw new IllegalStateException(getRel().getName()
						+ ": ground column '" + cols[i].getName() + "' is unbound at application");
			}
		}
	}

	@Override
	default long answers(Substitutions s) {
		Reified<?> image = MiniKanren.reify(s,
						lval(Tuple.ofAll(getArgs().map(u -> ((Unifiable<?>) s.walk(u)).getObjectTerm()).toJavaArray()))
								.getObjectTerm())
				.ground();
		return estimate(Call.of(getRel(), image));
	}
}
