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
import org.clauseway.pldb.relations.Property;
import org.clauseway.pldb.relations.Relation;
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

	/**
	 * The one door, namespaced: the class joins the relation's IDENTITY —
	 * two authors' vocabularies cannot collide in tabling, knowledge
	 * identity, or the store — while the physical name stays the bare
	 * string: tables, endpoints, and mark rows are the backend's world
	 * and spell the name alone. The row comes next: a typed one through
	 * {@link LiteralBuilder#schema}, or an anonymous one named column by
	 * column through {@link LiteralBuilder#arg}.
	 */
	static LiteralBuilder relation(Class<?> namespace, String name) {
		return LiteralBuilder.of(namespace.getName(), name);
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
