package org.clauseway.pldb.relations.literals;

// ABOUTME: The ignored() marker: inline ∃-projection — set semantics over the
// ABOUTME: kept columns, honest ¬∃ under exclude, a real join var inside bodies.

import org.clauseway.logic.solving.Query;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.clauseway.logic.nogoods.Exclusion.exclude;
import static org.clauseway.logic.unification.structures.LOption.none;
import static org.clauseway.logic.unification.structures.LOption.some;
import static org.clauseway.logic.unification.terms.LVal.lval;
import static org.clauseway.logic.unification.terms.LVar.lvar;
import static org.clauseway.pldb.relations.Ignored.ignored;

import org.clauseway.logic.finitedomain.Ints;
import org.clauseway.logic.goals.Knowledge;

import java.util.List;
import java.util.stream.Collectors;
import org.clauseway.logic.unification.structures.LOption;
import org.clauseway.logic.unification.terms.Unifiable;

import org.clauseway.pldb.AnswerSource;
import org.clauseway.pldb.inmemory.AnswerStore;
import org.junit.Test;

public class IgnoredTest {

	private static External<?> person(AnswerSource db, Unifiable<Integer> id, Unifiable<String> name) {
		return Literal.relation(IgnoredTest.class, "person")
				.arg("id", id).indexed()
				.arg("name", name)
				.from(db);
	}

	private static External<?> edge(AnswerSource db, Unifiable<Integer> src, Unifiable<Integer> dst) {
		return Literal.relation(IgnoredTest.class, "edge")
				.arg("src", src).indexed()
				.arg("dst", dst)
				.from(db);
	}

	private static External<?> tagged(AnswerSource db, Unifiable<Integer> id, Unifiable<LOption<String>> tag) {
		return Literal.relation(IgnoredTest.class, "tagged")
				.arg("id", id).indexed()
				.arg("tag", tag)
				.from(db);
	}

	/** some(ignored()) is "present, don't care": the marker one level inside the option is claimed. */
	@Test
	public void aMarkerInsideSomeIsClaimedAndNegatesHonestly() {
		AnswerStore db = AnswerStore.empty().asserting(
				tagged(null, lval(1), some(lval("core"))),
				tagged(null, lval(2), none()));
		Unifiable<Integer> present = lvar();
		assertThat(Query.of(present.unifies(1)
				.and(exclude(tagged(db, present, some(ignored()))))).solve(present)
				.collect(Collectors.toList())).isEmpty();
		Unifiable<Integer> absent = lvar();
		assertThat(Query.of(absent.unifies(2)
				.and(exclude(tagged(db, absent, some(ignored()))))).solve(absent)
				.map(Object::toString)
				.collect(Collectors.toList())).containsExactly("{2}");
	}

	private static AnswerStore db() {
		return AnswerStore.empty().asserting(
				person(null, lval(1), lval("Ada")),
				person(null, lval(2), lval("Ada")),
				person(null, lval(3), lval("Kurt")),
				edge(null, lval(1), lval(2)),
				edge(null, lval(1), lval(3)),
				edge(null, lval(2), lval(3)));
	}

	@Test
	public void theProjectionDeliversSetSemantics() {
		// two ids share the name Ada: the projection onto name folds them —
		// bag semantics would deliver Ada twice
		Unifiable<String> name = lvar();
		List<String> names = Query.of(person(db(), ignored(), name)).solve(name)
				.map(Object::toString)
				.sorted()
				.collect(Collectors.toList());
		assertThat(names).containsExactly("{Ada}", "{Kurt}");
	}

	@Test
	public void negationOverTheProjectionIsHonestNotExists() {
		// the entry-1 semantics inline: ¬∃name.person(x, name) — a free lvar
		// here would hit the collapse pothole and the branch would survive
		Unifiable<Integer> x = lvar();
		assertThat(Query.of(x.unifies(1)
				.and(exclude(person(db(), x, ignored())))).solve(x)
				.collect(Collectors.toList())).isEmpty();

		Unifiable<Integer> y = lvar();
		assertThat(Query.of(y.unifies(9)
				.and(exclude(person(db(), y, ignored())))).solve(y)
				.map(Object::toString)
				.collect(Collectors.toList())).containsExactly("{9}");
	}

	@Test
	public void aProjectedLiteralJoinsLikeAnyOther() {
		// hasEdge(src) := edge(src, ∃dst) — two out-edges fold to one answer,
		// and the projection composes in a conjunction
		Unifiable<Integer> src = lvar();
		List<String> sources = Query.of(edge(db(), src, ignored())
				.and(person(db(), src, lvar()))).solve(src)
				.map(Object::toString)
				.sorted()
				.collect(Collectors.toList());
		assertThat(sources).containsExactly("{1}", "{2}");
	}

	@Test
	public void negationOverAProjectedRuleIsHonestNotExists() {
		// the library's availableCopy shape: ¬∃via.linked(x, via) where
		// linked is itself a solving literal
		Unifiable<Integer> x = lvar();
		assertThat(Query.of(x.unifies(1)
				.and(exclude(linked(db(), x, ignored())))).solve(x)
				.collect(Collectors.toList())).isEmpty();

		Unifiable<Integer> y = lvar();
		assertThat(Query.of(y.unifies(9)
				.and(exclude(linked(db(), y, ignored())))).solve(y)
				.map(Object::toString)
				.collect(Collectors.toList())).containsExactly("{9}");
	}

	private static Literal<?> linked(AnswerSource db, Unifiable<Integer> node, Unifiable<Integer> via) {
		return Literal.relation(IgnoredTest.class, "linked")
				.arg("node", node)
				.arg("via", via)
				.solving(edge(db, node, via));
	}

	/**
	 * The marker is a JOIN variable in the body: used twice, the projection
	 * must not sever the join — that is the leak that broke availableCopy.
	 */
	private static Literal<?> reciprocal(AnswerSource db, Unifiable<Integer> node, Unifiable<Integer> via) {
		return Literal.relation(IgnoredTest.class, "reciprocal")
				.arg("node", node)
				.arg("via", via)
				.solving(edge(db, node, via).and(edge(db, via, node)));
	}

	@Test
	public void aProjectedJoinVariableStaysOneVariable() {
		// no reciprocal pair exists, but node 2 has both an in-edge and an
		// out-edge — a severed join would deliver it.
		Unifiable<Integer> node = lvar();
		assertThat(Query.of(reciprocal(db(), node, ignored())).solve(node)
				.collect(Collectors.toList())).isEmpty();
	}

	@Test
	public void projectedOnAGroundColumnRefuses() {
		assertThatThrownBy(() -> Literal.relation(IgnoredTest.class, "person")
				.arg("id", ignored()).ground()
				.arg("name", lvar())
				.from(null))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("id");
	}

	/**
	 * The receipt the ignored() convention rests on: a free lvar in a
	 * don't-care column is NOT a projection under exclude. The engine cannot
	 * tell it from a variable a sibling will bind later, so the nogood never
	 * fires and the branch survives — person 1 exists, and the query says it
	 * does not.
	 */
	@Test
	public void aFreeVariableUnderExcludeIsNotAProjection() {
		Unifiable<Integer> x = lvar();
		assertThat(Query.of(x.unifies(1)
				.and(exclude(person(db(), x, lvar())))).solve(x)
				.map(Object::toString)
				.collect(Collectors.toList())).containsExactly("{1}");
	}

	/** A person by id, the name a body-local variable: the rule's head drops it, no marker needed. */
	private static Literal<?> known(AnswerSource db, Unifiable<Integer> id) {
		return Literal.relation(IgnoredTest.class, "known")
				.arg("id", id)
				.solving(person(db, id, lvar()));
	}

	/**
	 * Inside a rule body a fresh lvar is projected by the head: the rule
	 * names only the columns it keeps, so under exclude the denial is the
	 * honest ¬∃ without a marker — person 1 exists and the rule says so,
	 * person 9 does not and the rule says that too. The marker is for a
	 * literal that stands under exclude with no head above it.
	 */
	@Test
	public void aBodyLocalVariableIsProjectedByTheRuleHead() {
		Unifiable<Integer> x = lvar();
		assertThat(Query.of(x.unifies(1).and(exclude(known(db(), x)))).solve(x)
				.collect(Collectors.toList())).isEmpty();
		Unifiable<Integer> y = lvar();
		assertThat(Query.of(y.unifies(9).and(exclude(known(db(), y)))).solve(y)
				.map(Object::toString)
				.collect(Collectors.toList())).containsExactly("{9}");
	}

	/**
	 * A projected literal prices as the literal it projects: the source's
	 * estimate for the full probe, the marked columns free. A rule prices at
	 * the maximum because its body is opaque; the projection's body is one
	 * stored literal, and the planner must see it.
	 */
	@Test
	public void aProjectedLiteralPricesAsTheFullLiteral() {
		assertThat(person(db(), lval(1), ignored()).answers(Knowledge.empty()))
				.isEqualTo(person(db(), lval(1), lvar()).answers(Knowledge.empty()));
		assertThat(person(db(), ignored(), lval("Ada")).answers(Knowledge.empty()))
				.isEqualTo(person(db(), lvar(), lval("Ada")).answers(Knowledge.empty()));
		assertThat(person(db(), lval(1), ignored()).answers(Knowledge.empty())).isLessThan(Long.MAX_VALUE);
	}

	/** A marker no literal claimed is a mistake, not a variable: the first walk refuses it by what it is. */
	@Test
	public void aMarkerOutsideALiteralRefusesAtTheFirstWalk() {
		Unifiable<Integer> x = lvar();
		assertThatThrownBy(() -> Query.of(x.unifies(ignored())).solve(x).collect(Collectors.toList()))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("ignored()")
				.hasMessageContaining("literal");
	}

	/** A projected column the body constrains arithmetically: the marker is a variable to the finite domain too. */
	@Test
	public void aProjectedColumnMayMeetArithmeticInTheBody() {
		Unifiable<Integer> src = lvar();
		List<String> sources = Query.of(shifted(db(), src, ignored())).solve(src)
				.map(Object::toString)
				.sorted()
				.collect(Collectors.toList());
		assertThat(sources).containsExactly("{1}", "{2}");
	}

	private static Literal<?> shifted(AnswerSource db, Unifiable<Integer> src, Unifiable<Integer> shifted) {
		Unifiable<Integer> dst = lvar();
		return Literal.relation(IgnoredTest.class, "shifted")
				.arg("src", src)
				.arg("shifted", shifted)
				.solving(edge(db, src, dst).and(Ints.addo(dst, lval(1), shifted)));
	}
}
