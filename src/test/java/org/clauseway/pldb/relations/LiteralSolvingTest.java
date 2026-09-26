package org.clauseway.pldb.relations;

// ABOUTME: Rule-backed literals: solving routes through the solve-scoped table,
// ABOUTME: value-equal mints share entries, method recursion seals, no self handle.

import static org.assertj.core.api.Assertions.assertThat;
import static org.clauseway.logic.goals.Goal.defer;
import static org.clauseway.logic.unification.terms.LVal.lval;
import static org.clauseway.logic.unification.terms.LVar.lvar;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.clauseway.functional.fibers.schedulers.BreadthFirstScheduler;
import org.clauseway.functional.tuples.Tuple;
import org.clauseway.logic.goals.Goal;
import org.clauseway.logic.goals.Knowledge;
import org.clauseway.logic.tabling.table.Table;
import org.clauseway.logic.unification.terms.Term;
import org.clauseway.logic.unification.terms.Unifiable;
import org.clauseway.pldb.AnswerSource;
import org.clauseway.pldb.inmemory.AnswerStore;
import org.junit.Test;

public class LiteralSolvingTest {

	private static Literal edge(AnswerSource db, Unifiable<Integer> src, Unifiable<Integer> dst) {
		return Literal.relation(LiteralSolvingTest.class, "edge")
				.arg("src", src).indexed()
				.arg("dst", dst).indexed()
				.from(db);
	}

	private static AnswerStore edges(int[][] pairs) {
		return AnswerStore.empty()
				.asserting(Arrays.stream(pairs)
						.map(p -> edge(null, lval(p[0]), lval(p[1])))
						.collect(Collectors.toList()));
	}

	/** The residence arc's target: recursion by calling the METHOD. */
	private static Literal reach(AnswerSource db, Unifiable<Integer> x, Unifiable<Integer> y) {
		return Literal.relation(LiteralSolvingTest.class, "reach")
				.arg("from", x)
				.arg("to", y)
				.solving(edge(db, x, y)
						.or(defer(() -> {
							Unifiable<Integer> z = lvar();
							return reach(db, x, z).and(edge(db, z, y));
						})));
	}

	private static List<String> answers(Goal g, Unifiable<?> out) {
		return g.solve(out).map(Object::toString).sorted().collect(Collectors.toList());
	}

	@Test(timeout = 5000)
	public void aRuleLiteralEnumerates() {
		AnswerStore db = edges(new int[][]{{1, 2}, {1, 3}});
		Unifiable<Integer> x = lvar();
		Unifiable<Integer> y = lvar();
		Literal direct = Literal.relation(LiteralSolvingTest.class, "direct")
				.arg("from", x)
				.arg("to", y)
				.solving(edge(db, x, y));
		assertThat(answers(x.unifies(1).and(direct), y)).containsExactlyInAnyOrder("{2}", "{3}");
	}

	@Test(timeout = 5000)
	public void valueEqualMintsShareOneProduction() {
		AnswerStore db = edges(new int[][]{{1, 2}, {1, 3}});
		AtomicInteger productions = new AtomicInteger();
		Unifiable<Integer> one = lvar();
		Unifiable<Integer> a = lvar();
		Unifiable<Integer> b = lvar();
		// two mints of one definition: distinct Literal values, value-equal relations
		Goal first = counted(db, productions, one, a);
		Goal second = counted(db, productions, one, b);
		List<String> pairs = one.unifies(1).and(first).and(second)
				.solve(lval(Tuple.of(a, b)))
				.map(Term::get)
				.map(t -> t._1.get() + "," + t._2.get())
				.sorted()
				.collect(Collectors.toList());
		assertThat(pairs).containsExactlyInAnyOrder("2,2", "2,3", "3,2", "3,3");
		assertThat(productions.get()).isEqualTo(1);
	}

	private static Literal counted(AnswerSource db, AtomicInteger productions,
			Unifiable<Integer> x, Unifiable<Integer> y) {
		return Literal.relation(LiteralSolvingTest.class, "counted")
				.arg("from", x)
				.arg("to", y)
				.solving(defer(() -> {
					productions.incrementAndGet();
					return edge(db, x, y);
				}));
	}

	@Test(timeout = 5000)
	public void methodRecursionSealsAChain() {
		AnswerStore db = edges(new int[][]{{1, 2}, {2, 3}, {3, 4}});
		Unifiable<Integer> x = lvar();
		Unifiable<Integer> y = lvar();
		assertThat(answers(x.unifies(1).and(reach(db, x, y)), y))
				.containsExactlyInAnyOrder("{2}", "{3}", "{4}");
	}

	@Test(timeout = 5000)
	public void methodRecursionSealsACycle() {
		AnswerStore db = edges(new int[][]{{1, 2}, {2, 3}, {3, 1}});
		Unifiable<Integer> x = lvar();
		Unifiable<Integer> y = lvar();
		assertThat(answers(x.unifies(1).and(reach(db, x, y)), y))
				.containsExactlyInAnyOrder("{1}", "{2}", "{3}");
	}

	@Test(timeout = 5000)
	public void aDiamondFoldsDerivations() {
		AnswerStore db = edges(new int[][]{{1, 2}, {1, 3}, {2, 4}, {3, 4}});
		Unifiable<Integer> x = lvar();
		Unifiable<Integer> y = lvar();
		assertThat(answers(x.unifies(1).and(reach(db, x, y)), y))
				.containsExactlyInAnyOrder("{2}", "{3}", "{4}");
	}

	@Test(timeout = 5000)
	public void aHoistedLiteralServesTwoKeys() {
		AnswerStore db = edges(new int[][]{{1, 2}, {2, 3}});
		Unifiable<Integer> x = lvar();
		Unifiable<Integer> y = lvar();
		Literal hoisted = reach(db, x, y);
		// one mint, two branches, different key bindings per branch
		List<String> both = x.unifies(1).and(hoisted).and(y.unifies(2))
				.or(x.unifies(2).and(hoisted).and(y.unifies(3)))
				.solve(lval(Tuple.of(x, y)))
				.map(Term::get)
				.map(t -> t._1.get() + "," + t._2.get())
				.sorted().collect(Collectors.toList());
		assertThat(both).containsExactlyInAnyOrder("1,2", "2,3");
	}

	@Test(timeout = 5000)
	public void tablesAreFreshPerSolve() {
		// the residence doctrine: a solve roots its own table, so nothing is
		// memoized across solves unless the caller threads a table forward
		AnswerStore db = edges(new int[][]{{1, 2}});
		AtomicInteger productions = new AtomicInteger();
		Unifiable<Integer> x = lvar();
		Unifiable<Integer> y = lvar();
		Literal lit = counted(db, productions, x, y);
		assertThat(answers(x.unifies(1).and(lit), y)).containsExactly("{2}");
		Unifiable<Integer> x2 = lvar();
		Unifiable<Integer> y2 = lvar();
		assertThat(answers(x2.unifies(1).and(counted(db, productions, x2, y2)), y2))
				.containsExactly("{2}");
		assertThat(productions.get()).isEqualTo(2);
	}

	@Test(timeout = 5000)
	public void aSeededSolveReplaysTheRetainedTable() {
		// warm start as the caller's explicit act: thread the same table into
		// a second solve and the rule never re-derives — the capability the
		// owned table used to provide, now a value the caller holds
		AnswerStore db = edges(new int[][]{{1, 2}});
		AtomicInteger productions = new AtomicInteger();
		Table retained = Table.empty();
		Unifiable<Integer> x = lvar();
		Unifiable<Integer> y = lvar();
		List<String> first = x.unifies(1).and(counted(db, productions, x, y))
				.solveFrom(Knowledge.empty().withStore(retained), y, BreadthFirstScheduler::new)
				.map(Object::toString).collect(Collectors.toList());
		Unifiable<Integer> x2 = lvar();
		Unifiable<Integer> y2 = lvar();
		List<String> second = x2.unifies(1).and(counted(db, productions, x2, y2))
				.solveFrom(Knowledge.empty().withStore(retained), y2, BreadthFirstScheduler::new)
				.map(Object::toString).collect(Collectors.toList());
		assertThat(second).isEqualTo(first).containsExactly("{2}");
		assertThat(productions.get())
				.describedAs("the second solve replays the seeded table")
				.isEqualTo(1);
	}

	@Test(timeout = 5000)
	public void ruleAnswersAgreeWithTheBareLookup() {
		AnswerStore db = edges(new int[][]{{1, 2}, {1, 3}, {2, 4}});
		Unifiable<Integer> a = lvar();
		Unifiable<Integer> b = lvar();
		Unifiable<Integer> x = lvar();
		Unifiable<Integer> y = lvar();
		Literal viaRule = Literal.relation(LiteralSolvingTest.class, "viaRule")
				.arg("from", x)
				.arg("to", y)
				.solving(edge(db, x, y));
		assertThat(answers(x.unifies(1).and(viaRule), y))
				.containsExactlyInAnyOrder("{2}", "{3}")
				.isEqualTo(answers(a.unifies(1).and(edge(db, a, b)), b));
	}
}
