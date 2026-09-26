package org.clauseway.pldb.relations;

// ABOUTME: Answers-as-rows receipts: each answer grounds every template, constants
// ABOUTME: ride, clusters land whole, and a free cell rides wide for the doors to judge.

import static org.assertj.core.api.Assertions.assertThat;
import static org.clauseway.logic.nogoods.Exclusion.exclude;
import static org.clauseway.logic.unification.terms.LVal.lval;
import static org.clauseway.logic.unification.terms.LVar.lvar;

import java.util.List;
import java.util.stream.Collectors;
import org.clauseway.functional.fibers.schedulers.BreadthFirstScheduler;
import org.clauseway.logic.goals.Goal;
import org.clauseway.logic.solving.Condition;
import org.clauseway.logic.unification.terms.Unifiable;
import org.junit.Test;

public class QuestionTest {

	private static Literal loan(Unifiable<Integer> id, Unifiable<String> copy) {
		return Literal.relation(QuestionTest.class, "loan")
				.arg("loanId", id).indexed()
				.arg("copy", copy)
				.from(null);
	}

	private static Literal returned(Unifiable<Integer> id) {
		return Literal.relation(QuestionTest.class, "returned")
				.arg("loanId", id).indexed()
				.from(null);
	}

	/** The test's engine choice, stated where the doctrine wants it. */
	private static List<Answer> selected(Goal question, Literal... schemas) {
		return new BreadthFirstScheduler<>(Question.select(question, schemas)).get();
	}

	private static String render(Answer row) {
		return row.getRelation().getName() + row.values();
	}

	@Test
	public void oneAnswerGroundsTheWholeCluster() {
		Unifiable<Integer> id = lvar();
		Unifiable<String> copy = lvar();
		Goal question = id.unifies(1).and(copy.unifies("c1"));

		List<String> facts = selected(question, loan(id, copy), returned(id)).stream()
				.map(QuestionTest::render)
				.collect(Collectors.toList());

		assertThat(facts).containsExactly("loan[1, c1]", "returned[1]");
	}

	@Test
	public void everyAnswerContributesItsOwnCluster() {
		Unifiable<Integer> id = lvar();
		Unifiable<String> copy = lvar();
		Goal question = copy.unifies("c1")
				.and(id.unifies(1).or(id.unifies(2)));

		List<String> facts = selected(question, loan(id, copy), returned(id)).stream()
				.map(QuestionTest::render)
				.sorted()
				.collect(Collectors.toList());

		assertThat(facts).containsExactly(
				"loan[1, c1]", "loan[2, c1]", "returned[1]", "returned[2]");
	}

	@Test
	public void aConstantTemplateCellRidesIntoTheFact() {
		Unifiable<Integer> id = lvar();
		Goal question = id.unifies(7);

		List<String> facts = selected(question, loan(id, lval("archived"))).stream()
				.map(QuestionTest::render)
				.collect(Collectors.toList());

		assertThat(facts).containsExactly("loan[7, archived]");
	}

	@Test
	public void aFreeTemplateCellRidesWideForTheDoorsToJudge() {
		// select stops refusing: the unbound copy rides as a WIDE cell —
		// typed access answers empty there and the write doors, not the
		// select, own the strictness
		Unifiable<Integer> id = lvar();
		Unifiable<String> copy = lvar();
		Goal question = id.unifies(1);

		List<Answer> wide = selected(question, loan(id, copy));
		assertThat(wide).hasSize(1);
		assertThat(wide.get(0).<Integer> get(Property.of("loanId"))).contains(1);
		assertThat(wide.get(0).<String> get(Property.of("copy"))).isEmpty();
	}

	@Test
	public void aGuardedDerivationDeliversItsCondition() {
		// the guard survives to the row: wide at copy AND conditional —
		// the same shape the produce seam mints, now at the front door
		Unifiable<Integer> id = lvar();
		Unifiable<String> copy = lvar();
		Goal question = id.unifies(1).and(exclude(copy.unifies("c9")));

		List<Answer> rows = selected(question, loan(id, copy));
		assertThat(rows).hasSize(1);
		assertThat(rows.get(0).getCondition())
				.describedAs("the derivation's guard rides the row")
				.isNotEqualTo(Condition.ONE);
		assertThat(rows.get(0).<String> get(Property.of("copy"))).isEmpty();
		assertThat(rows.get(0).unconditional().getCondition())
				.describedAs("the explicit strengthening drops the guard")
				.isEqualTo(Condition.ONE);
	}

	@Test
	public void theClusterSharesTheDerivationsGuard() {
		Unifiable<Integer> id = lvar();
		Unifiable<String> copy = lvar();
		Goal question = id.unifies(1).and(exclude(copy.unifies("c9")));

		List<Answer> rows = selected(question, loan(id, copy), returned(id));
		assertThat(rows).hasSize(2);
		assertThat(rows.get(0).getCondition())
				.describedAs("one derivation, one guard — every row of the cluster carries it")
				.isEqualTo(rows.get(1).getCondition())
				.isNotEqualTo(Condition.ONE);
	}

	@Test
	public void groundAnswersStayUnconditional() {
		Unifiable<Integer> id = lvar();
		Unifiable<String> copy = lvar();
		Goal question = id.unifies(1).and(copy.unifies("c1"));

		assertThat(selected(question, loan(id, copy), returned(id)).stream()
				.map(Answer::getCondition)
				.collect(Collectors.toList()))
				.containsExactly(Condition.ONE, Condition.ONE);
	}

	@Test
	public void zeroAnswersStreamNothing() {
		Unifiable<Integer> id = lvar();
		Goal question = id.unifies(1).and(id.unifies(2));

		assertThat(selected(question, loan(id, lval("c1")))).isEmpty();
	}
}
