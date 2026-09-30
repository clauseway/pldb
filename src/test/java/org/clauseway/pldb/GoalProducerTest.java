package org.clauseway.pldb;

// ABOUTME: The produce bridge: probes memoize through the injected table, wide
// ABOUTME: sealed entries serve narrow probes, conditional cells deliver converged.

import org.clauseway.logic.solving.Query;
import static org.assertj.core.api.Assertions.assertThat;
import static org.clauseway.logic.nogoods.Exclusion.exclude;
import static org.clauseway.logic.unification.terms.LVal.lval;
import static org.clauseway.logic.unification.terms.LVar.lvar;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.clauseway.functional.Nothing;
import org.clauseway.functional.fibers.Fiber;
import org.clauseway.functional.fibers.schedulers.BreadthFirstScheduler;
import org.clauseway.logic.goals.Goal;
import org.clauseway.logic.solving.Call;
import org.clauseway.logic.tabling.table.Table;
import org.clauseway.logic.unification.terms.Any;
import org.clauseway.logic.unification.terms.Term;
import org.clauseway.logic.unification.terms.Unifiable;
import org.clauseway.pldb.inmemory.AnswerStore;
import org.clauseway.logic.solving.Answer;
import org.clauseway.pldb.relations.Answers;
import org.clauseway.pldb.relations.Literal;
import org.clauseway.pldb.relations.Relation;
import org.junit.Test;

public class GoalProducerTest {

	private static Literal person(AnswerSource db, Unifiable<Long> id, Unifiable<String> name) {
		return Literal.relation(GoalProducerTest.class, "person").arg("id", id).arg("name", name).from(db);
	}

	private static Literal person(AnswerProducer p, Unifiable<Long> id, Unifiable<String> name) {
		return Literal.relation(GoalProducerTest.class, "person").arg("id", id).arg("name", name).produced(p);
	}

	private static Relation personRel() {
		return person((AnswerSource) null, lvar(), lvar()).getRel();
	}

	private final AnswerStore db = AnswerStore.empty()
			.asserting(Arrays.asList(
					person((AnswerSource) null, lval(1L), lval("Ada")),
					person((AnswerSource) null, lval(2L), lval("Alan")),
					person((AnswerSource) null, lval(3L), lval("Kurt"))));

	private final AtomicInteger hits = new AtomicInteger();

	/** The reference database as a source, counting every fetch. */
	private AnswerSource counting() {
		return probe -> {
			hits.incrementAndGet();
			return db.answers(probe);
		};
	}

	/** The counting lookup as a rule over its own private table. */
	private GoalProducer producer() {
		Unifiable<Long> id = lvar();
		Unifiable<String> name = lvar();
		return GoalProducer.of(personRel(),
				person(counting(), id, name),
				Arrays.asList(id, name), Table.empty());
	}

	/** A probe image: bound slots carry their value, nulls are free. */
	private Call<Relation> probe(Object... slots) {
		List<Term<?>> members = new ArrayList<>();
		int frees = 0;
		for (Object slot : slots) {
			members.add(slot == null ? Any.of(frees++) : lval(slot));
		}
		return Call.of(personRel(), Answers.image(members));
	}

	/** Drive produce to completion, collecting the emissions. */
	private static List<Answer<Relation>> drain(AnswerProducer source, Call<Relation> probe) {
		List<Answer<Relation>> collected = new ArrayList<>();
		new BreadthFirstScheduler<>(source.produce(probe, answer -> {
			collected.add(answer);
			return Fiber.done(Nothing.nothing());
		})).get();
		return collected;
	}

	@Test
	public void probesMemoizeThroughTheInjectedTable() {
		GoalProducer source = producer();
		assertThat(drain(source, probe(null, null))).hasSize(3);
		assertThat(drain(source, probe(null, null))).hasSize(3);
		assertThat(hits.get())
				.describedAs("the second identical probe joins the sealed entry")
				.isEqualTo(1);
	}

	@Test
	public void aWideSealedEntryServesTheNarrowProbe() {
		// call subsumption at the data boundary: the narrow probe reads the
		// wide entry through consume's unification filter — exactly the
		// answers it asked for, and the wrapped source is never re-hit
		GoalProducer source = producer();
		drain(source, probe(null, null));
		List<Answer<Relation>> narrow = drain(source, probe(2L, null));
		assertThat(hits.get()).isEqualTo(1);
		assertThat(narrow).hasSize(1);
	}

	@Test
	public void duplicateArrivalsFoldInTheCell() {
		// dedup is the cell join's own algebra: a duplicate arrival is an
		// inert fold — no log entry, no emission
		// minted directly: the db's unindexed probe over-delivers by license,
		// so iterator().next() was order-dependent debris
		Answer<Relation> row = Answers.answer(personRel(), Arrays.asList((Object) 2L, "Alan"));
		AnswerSource stuttering = probe -> Arrays.asList(row, row, row);
		Unifiable<Long> id = lvar();
		Unifiable<String> name = lvar();
		GoalProducer source = GoalProducer.of(personRel(),
				person(stuttering, id, name),
				Arrays.asList(id, name), Table.empty());
		assertThat(drain(source, probe(2L, null))).hasSize(1);
	}

	private static List<String> answers(Goal goal, Unifiable<?> out) {
		return Query.of(goal).solve(out)
				.map(Object::toString)
				.sorted()
				.collect(Collectors.toList());
	}

	@Test
	public void aConditionalAnswerImposesAtTheConsumer() {
		// the body binds nothing and forbids one value: the entry's single
		// answer is CONDITIONAL — free image, a nogood riding the condition —
		// and delivery restates it at the consumer, where it decides
		Unifiable<Long> gid = lvar();
		Unifiable<String> gname = lvar();
		GoalProducer guarded = GoalProducer.of(personRel(),
				exclude(gid.unifies(2L)), Arrays.asList(gid, gname), Table.empty());
		Unifiable<Long> id = lvar();
		assertThat(answers(person(guarded, id, lvar())
				.and(id.unifies(1L)), id)).containsExactly("{1}");
		Unifiable<Long> refused = lvar();
		assertThat(answers(person(guarded, refused, lvar())
				.and(refused.unifies(2L)), refused)).isEmpty();
	}

	@Test
	public void anAscendedConditionDeliversOnlyConverged() {
		// the same free image succeeds under two guards, so the cell ASCENDS:
		// {≠2}, then {≠2}∨{≠3}; each conjunct of the joined condition
		// delivers exactly once whatever the arrival order
		Unifiable<Long> gid = lvar();
		Unifiable<String> gname = lvar();
		GoalProducer guarded = GoalProducer.of(personRel(),
				exclude(gid.unifies(2L)).or(exclude(gid.unifies(3L))),
				Arrays.asList(gid, gname), Table.empty());
		Unifiable<Long> id = lvar();
		assertThat(answers(person(guarded, id, lvar())
				.and(id.unifies(4L)), id)).containsExactlyInAnyOrder("{4}", "{4}");
	}

	@Test
	public void anAbsorbedConditionIsNeverDelivered() {
		// one disjunct guards the image, the other admits it outright: the
		// conditions join to 1 by absorption — one branch, not two
		Unifiable<Long> gid = lvar();
		Unifiable<String> gname = lvar();
		GoalProducer guarded = GoalProducer.of(personRel(),
				exclude(gid.unifies(2L)).or(Goal.success()),
				Arrays.asList(gid, gname), Table.empty());
		Unifiable<Long> id = lvar();
		assertThat(answers(person(guarded, id, lvar())
				.and(id.unifies(4L)), id)).containsExactly("{4}");
	}
}
