package org.clauseway.pldb.sql;

// ABOUTME: The pool speaks the whole entry shape: conditional answers land WITH
// ABOUTME: their guards, serve from coverage, and never re-hit the delegate.

import static org.assertj.core.api.Assertions.assertThat;
import static org.clauseway.logic.nogoods.Exclusion.exclude;
import static org.clauseway.logic.unification.terms.LVar.lvar;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.clauseway.functional.Nothing;
import org.clauseway.functional.fibers.Fiber;
import org.clauseway.functional.fibers.schedulers.BreadthFirstScheduler;
import org.clauseway.logic.solving.Condition;
import org.clauseway.logic.solving.Call;
import org.clauseway.logic.tabling.table.Table;
import org.clauseway.logic.unification.terms.Any;
import org.clauseway.logic.unification.terms.Unifiable;
import org.clauseway.pldb.AnswerSource;
import org.clauseway.pldb.GoalProducer;
import org.clauseway.pldb.relations.Answer;
import org.clauseway.pldb.relations.Answers;
import org.clauseway.pldb.relations.Literal;
import org.clauseway.pldb.relations.Relation;
import org.junit.Test;

public class CachingAnswerSourceTest {

	private static final Relation GUARDED = Literal.relation(CachingAnswerSourceTest.class, "guarded")
			.arg("id", lvar()).indexed()
			.arg("tag", lvar())
			.from(null)
			.getRel();

	private static Call<Relation> everything() {
		return Call.of(GUARDED, Answers.image(Any.of(0), Any.of(1)));
	}

	/** A REAL conditional answer, minted by a produce whose body forbids one id. */
	private static Answer conditional() {
		Unifiable<Object> id = lvar();
		Unifiable<Object> tag = lvar();
		GoalProducer guarded = GoalProducer.of(GUARDED,
				exclude(id.unifies(9L)), Arrays.asList(id, tag), Table.empty());
		List<Answer> delivered = new ArrayList<>();
		new BreadthFirstScheduler<>(guarded.produce(everything(), one -> {
			delivered.add(one);
			return Fiber.done(Nothing.nothing());
		})).get();
		return delivered.get(0);
	}

	@Test
	public void aConditionalAnswerLandsWithItsGuardAndServesFromCoverage() {
		Answer guarded = conditional();
		assertThat(guarded.getCondition()).isNotEqualTo(Condition.ONE);

		AtomicInteger hits = new AtomicInteger();
		AnswerSource delegate = probe -> {
			hits.incrementAndGet();
			return Collections.singletonList(guarded);
		};
		CachingAnswerSource cached = CachingAnswerSource.over(delegate);

		Answer first = cached.answers(everything()).iterator().next();
		assertThat(first.getCondition())
				.describedAs("the pool keeps the guard whole — the ground-pool refusal is dead")
				.isEqualTo(guarded.getCondition());

		Answer again = cached.answers(everything()).iterator().next();
		assertThat(again.getCondition()).isEqualTo(guarded.getCondition());
		assertThat(hits.get())
				.describedAs("the covered repeat serves from the pool")
				.isEqualTo(1);
	}
}
