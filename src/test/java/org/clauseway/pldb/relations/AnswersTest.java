package org.clauseway.pldb.relations;

// ABOUTME: The answer codec: a ground row encodes as (reified image, ONE); the image
// ABOUTME: decodes per position — cells in Term vocabulary, values for rows.

import org.clauseway.logic.solving.Answer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.clauseway.logic.nogoods.Exclusion.exclude;
import static org.clauseway.logic.unification.terms.LVal.lval;
import static org.clauseway.logic.unification.terms.LVar.lvar;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.clauseway.functional.Nothing;
import org.clauseway.functional.fibers.Fiber;
import org.clauseway.functional.fibers.schedulers.BreadthFirstScheduler;
import org.clauseway.logic.solving.Condition;
import org.clauseway.logic.solving.Call;
import org.clauseway.logic.tabling.table.Table;
import org.clauseway.logic.unification.terms.Any;
import org.clauseway.logic.unification.terms.Term;
import org.clauseway.logic.unification.terms.Unifiable;
import org.clauseway.pldb.GoalProducer;
import org.junit.Test;

public class AnswersTest {

	private final Relation person = Literal.relation(AnswersTest.class, "person")
			.arg("id", lvar()).arg("name", lvar()).from(null).getRel();

	@Test
	public void aFactEncodesAsAGroundRowAtOne() {
		Answer<Relation> answer = Answers.answer(person, Arrays.asList(1L, "Alan"));
		assertThat(answer.getCondition()).isEqualTo(Condition.ONE);
		assertThat(answer.getReified().isGround()).isTrue();
		assertThat(Answers.values(answer.getReified())).containsExactly(1L, "Alan");
	}

	private Answer<Relation> ground(Object id, Object name) {
		return Answers.answer(person, Arrays.asList(id, name));
	}

	private Answer<Relation> answer(Condition condition, Term<?>... cells) {
		return Answer.of(person, Answers.image(cells), condition);
	}

	/** A REAL guard, minted by a produce whose body forbids one id. */
	private Condition forbidding(long id) {
		Unifiable<Object> a = lvar();
		Unifiable<Object> b = lvar();
		GoalProducer guarded = GoalProducer.of(person,
				exclude(a.unifies(id)), Arrays.asList(a, b), Table.empty());
		List<Answer<Relation>> delivered = new ArrayList<>();
		new BreadthFirstScheduler<>(guarded.produce(
				Call.of(person, Answers.image(Any.of(0), Any.of(1))), one -> {
					delivered.add(one);
					return Fiber.done(Nothing.nothing());
				})).get();
		return delivered.get(0).getCondition();
	}

	@Test
	public void aWideRowSubsumesItsInstancesAndNeverTheReverse() {
		Answer<Relation> wide = answer(Condition.ONE, Any.of(0), lval("Alan"));
		assertThat(Answers.subsumes(wide, ground(1L, "Alan"))).isTrue();
		assertThat(Answers.subsumes(wide, ground(1L, "Ada")))
				.describedAs("the wide's ground cell must match")
				.isFalse();
		assertThat(Answers.subsumes(ground(1L, "Alan"), wide))
				.describedAs("a ground row never subsumes the wide claiming more")
				.isFalse();
	}

	@Test
	public void aCoupledWideClaimsOnlyTheDiagonal() {
		Answer<Relation> coupled = answer(Condition.ONE, Any.of(0), Any.of(0));
		assertThat(Answers.subsumes(coupled, ground(7L, 7L))).isTrue();
		assertThat(Answers.subsumes(coupled, ground(1L, 2L)))
				.describedAs("inconsistent binding — dropping (1,2) would under-deliver")
				.isFalse();
	}

	@Test
	public void aGuardedWideNeverSwallowsTheUnconditional() {
		Condition guarded = forbidding(9L);
		Answer<Relation> guardedWide = answer(guarded, Any.of(0), Any.of(1));
		assertThat(Answers.subsumes(guardedWide, ground(1L, "Alan")))
				.describedAs("the ground row's ONE outlives the wide's guard")
				.isFalse();
		Answer<Relation> openWide = answer(Condition.ONE, Any.of(0), Any.of(1));
		assertThat(Answers.subsumes(openWide,
				Answer.of(person, ground(1L, "Alan").getReified(), guarded)))
				.describedAs("ONE absorbs any guard — the open wide covers the guarded row")
				.isTrue();
	}

	@Test
	public void valuesAreRawNotRendered() {
		// the braces gotcha: a reified term's toString renders decoration;
		// the codec hands back the VALUES, never their rendering
		Object first = Answers.values(
				Answers.answer(person, Arrays.asList(1L, "Alan")).getReified()).get(0);
		assertThat(first).isInstanceOf(Long.class).isEqualTo(1L);
	}

}
