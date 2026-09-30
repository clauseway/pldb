package org.clauseway.pldb;

// ABOUTME: posted() over rule literals: the propagator reads the solve's shared
// ABOUTME: table — negation shapes, both dual-driver orders, recursive closure.

import org.clauseway.logic.solving.Query;
import static org.assertj.core.api.Assertions.assertThat;
import static org.clauseway.logic.goals.Goal.defer;
import static org.clauseway.logic.nogoods.Exclusion.exclude;
import static org.clauseway.logic.unification.terms.LVal.lval;
import static org.clauseway.logic.unification.terms.LVar.lvar;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import org.clauseway.functional.tuples.Tuple;
import org.clauseway.logic.goals.Goal;
import org.clauseway.logic.unification.terms.Term;
import org.clauseway.logic.unification.terms.Unifiable;
import org.clauseway.pldb.inmemory.AnswerStore;
import org.clauseway.pldb.relations.Literal;
import org.junit.Test;

public class RuleNegationTest {

	private static Literal p(Unifiable<Integer> i, Unifiable<String> t) {
		return Literal.relation(RuleNegationTest.class, "p")
				.arg("item", i)
				.arg("tag", t)
				.solving(i.unifies(1).and(t.unifies("a"))
						.or(i.unifies(2).and(t.unifies("b"))));
	}

	private static List<String> answers(Goal g, Unifiable<?> out) {
		return Query.of(g).solve(out).map(Object::toString).sorted().collect(Collectors.toList());
	}

	@Test(timeout = 5000)
	public void groundRowsNegateToForbiddenTuples() {
		Unifiable<Integer> x = lvar();
		Unifiable<String> y = lvar();
		assertThat(answers(exclude(p(x, y)).and(x.unifies(1)).and(y.unifies("a")), x)).isEmpty();
		Unifiable<Integer> x2 = lvar();
		Unifiable<String> y2 = lvar();
		assertThat(answers(exclude(p(x2, y2)).and(x2.unifies(2)).and(y2.unifies("c")), x2))
				.containsExactly("{2}");
	}

	@Test(timeout = 5000)
	public void aWideRowNegatesToAStrongerExclusion() {
		Unifiable<Integer> x = lvar();
		Unifiable<String> y = lvar();
		Literal wide = Literal.relation(RuleNegationTest.class, "wide")
				.arg("item", x)
				.arg("tag", y)
				.solving(x.unifies(1));
		assertThat(answers(exclude(wide).and(x.unifies(1)).and(y.unifies("z")), x)).isEmpty();
		Unifiable<Integer> x2 = lvar();
		Unifiable<String> y2 = lvar();
		Literal wide2 = Literal.relation(RuleNegationTest.class, "wide")
				.arg("item", x2)
				.arg("tag", y2)
				.solving(x2.unifies(1));
		assertThat(answers(exclude(wide2).and(x2.unifies(2)).and(y2.unifies("z")), x2))
				.containsExactly("{2}");
	}

	@Test(timeout = 5000)
	public void aConditionalRowNegatesAsAFilter() {
		Unifiable<Integer> x = lvar();
		Unifiable<String> y = lvar();
		Literal guarded = Literal.relation(RuleNegationTest.class, "guarded")
				.arg("item", x)
				.arg("tag", y)
				.solving(x.unifies(1).and(exclude(y.unifies("q"))));
		assertThat(answers(exclude(guarded).and(x.unifies(1)).and(y.unifies("z")), x)).isEmpty();
		Unifiable<Integer> x2 = lvar();
		Unifiable<String> y2 = lvar();
		Literal guarded2 = Literal.relation(RuleNegationTest.class, "guarded")
				.arg("item", x2)
				.arg("tag", y2)
				.solving(x2.unifies(1).and(exclude(y2.unifies("q"))));
		assertThat(answers(exclude(guarded2).and(x2.unifies(1)).and(y2.unifies("q")), x2))
				.containsExactly("{1}");
	}

	@Test(timeout = 5000)
	public void postedAgreesWithBareConsumption() {
		Unifiable<Integer> x = lvar();
		Unifiable<String> y = lvar();
		List<String> viaPosted = answers(p(x, y).posted().and(x.unifies(1)), y);
		Unifiable<Integer> x2 = lvar();
		Unifiable<String> y2 = lvar();
		List<String> viaBare = answers(x2.unifies(1).and(p(x2, y2)), y2);
		assertThat(viaPosted).isEqualTo(viaBare).containsExactly("{a}");
	}

	@Test(timeout = 5000)
	public void postedThenConsumedAgree() {
		// posted and bare share the solve's table: whichever driver arrives
		// first produces, the other reads the same entries
		Unifiable<Integer> x = lvar();
		Unifiable<String> y = lvar();
		Unifiable<Integer> a = lvar();
		Unifiable<String> b = lvar();
		List<String> both = Query.of(p(x, y).posted().and(x.unifies(1))
				.and(p(a, b)).and(a.unifies(2))).solve(lval(Tuple.of(y, b)))
				.map(Term::get)
				.map(t -> t._1.get() + "," + t._2.get())
				.collect(Collectors.toList());
		assertThat(both).containsExactly("a,b");
	}

	@Test(timeout = 5000)
	public void consumedThenPostedAgree() {
		// order reversed: the search claims first, the propagator awaits the seal
		Unifiable<Integer> x = lvar();
		Unifiable<String> y = lvar();
		Unifiable<Integer> a = lvar();
		Unifiable<String> b = lvar();
		List<String> both = Query.of(x.unifies(1).and(p(x, y))
				.and(p(a, b).posted()).and(a.unifies(2))).solve(lval(Tuple.of(y, b)))
				.map(Term::get)
				.map(t -> t._1.get() + "," + t._2.get())
				.collect(Collectors.toList());
		assertThat(both).containsExactly("a,b");
	}

	@Test(timeout = 5000)
	public void aNegatedRecursiveClosureFilters() {
		AnswerStore db = AnswerStore.empty()
				.asserting(Arrays.asList(
						edge(null, lval(1), lval(2)),
						edge(null, lval(2), lval(3))));
		// reachable: 1→2, 1→3, 2→3; NOT reachable: (3, anything), (2,1), ...
		Unifiable<Integer> x = lvar();
		Unifiable<Integer> y = lvar();
		assertThat(answers(exclude(reach(db, x, y))
				.and(x.unifies(1)).and(y.unifies(3)), x)).isEmpty();
		Unifiable<Integer> x2 = lvar();
		Unifiable<Integer> y2 = lvar();
		assertThat(answers(exclude(reach(db, x2, y2))
				.and(x2.unifies(2)).and(y2.unifies(1)), x2)).containsExactly("{2}");
	}

	@Test(timeout = 5000)
	public void aCoupledRowNegatesToADisequality() {
		// the diagonal (Any0, Any0) negates to x != y
		Unifiable<String> x = lvar();
		Unifiable<String> y = lvar();
		Literal diagonal = Literal.relation(RuleNegationTest.class, "diag")
				.arg("l", x)
				.arg("r", y)
				.solving(x.unifies(y));
		assertThat(answers(exclude(diagonal).and(x.unifies("v")).and(y.unifies("v")), x)).isEmpty();
		Unifiable<String> x2 = lvar();
		Unifiable<String> y2 = lvar();
		Literal diagonal2 = Literal.relation(RuleNegationTest.class, "diag")
				.arg("l", x2)
				.arg("r", y2)
				.solving(x2.unifies(y2));
		assertThat(answers(exclude(diagonal2).and(x2.unifies("v")).and(y2.unifies("w")), x2))
				.containsExactly("{v}");
	}

	@Test(timeout = 5000)
	public void negationIsConstructiveOverFreeVariables() {
		// with y bound and x free the negation survives as the answer's
		// residue — the complement described, not enumerated
		Unifiable<Integer> x = lvar();
		Unifiable<String> y = lvar();
		List<String> free = answers(exclude(p(x, y)).and(y.unifies("a")), x);
		assertThat(free).hasSize(1);
		assertThat(free.get(0)).startsWith("_.0 : ");
	}

	@Test(timeout = 5000)
	public void negatedAnyIsUnconditionalFailure() {
		Unifiable<Integer> x = lvar();
		Literal tautology = Literal.relation(RuleNegationTest.class, "taut")
				.arg("item", x)
				.solving(Goal.success());
		assertThat(answers(exclude(tautology).and(x.unifies(2)), x)).isEmpty();
	}

	private static Literal edge(AnswerSource db, Unifiable<Integer> src, Unifiable<Integer> dst) {
		return Literal.relation(RuleNegationTest.class, "edge")
				.arg("src", src).indexed()
				.arg("dst", dst).indexed()
				.from(db);
	}

	private static Literal reach(AnswerSource db, Unifiable<Integer> x, Unifiable<Integer> y) {
		return Literal.relation(RuleNegationTest.class, "reach")
				.arg("from", x)
				.arg("to", y)
				.solving(edge(db, x, y)
						.or(defer(() -> {
							Unifiable<Integer> z = lvar();
							return reach(db, x, z).and(edge(db, z, y));
						})));
	}
}
