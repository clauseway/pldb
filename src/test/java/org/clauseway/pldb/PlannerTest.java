package org.clauseway.pldb;

// ABOUTME: Acceptance for the ordered planner: answers identical planned vs
// ABOUTME: unplanned, and a mis-ordered query enumerates an order of magnitude fewer facts.

import org.clauseway.logic.solving.Query;
import static org.assertj.core.api.Assertions.assertThat;
import static org.clauseway.logic.unification.terms.LVal.lval;
import static org.clauseway.logic.unification.terms.LVar.lvar;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import org.clauseway.logic.goals.optimizer.CascadingOptimizer;
import org.clauseway.logic.goals.optimizer.Optimizer;
import org.clauseway.logic.goals.optimizer.OrderingOptimizer;
import org.clauseway.logic.solving.Call;
import org.clauseway.logic.unification.terms.Unifiable;
import org.clauseway.pldb.inmemory.AnswerStore;
import org.clauseway.logic.solving.Answer;
import org.clauseway.pldb.relations.Literal;
import org.clauseway.pldb.relations.Property;
import org.clauseway.pldb.relations.Relation;
import org.junit.Test;

public class PlannerTest {

	private static Literal parent(AnswerSource db, Unifiable<Integer> parentId, Unifiable<Integer> childId) {
		return Literal.relation(PlannerTest.class, "parentP")
				.arg("parentId", parentId).indexed()
				.arg("childId", childId).indexed()
				.from(db);
	}

	private static Relation parentRel() {
		return parent(null, lvar(), lvar()).getRel();
	}

	private static final int N = 40;
	private static final Property<Integer> parentId = Property.of("parentId");
	private static final Property<Integer> childId = Property.of("childId");

	/** Counts facts the source yields — the probe metric of query-planning.md §Phase 2. */
	private static final class CountingSource implements AnswerSource {
		private final AnswerStore inner;
		private final AtomicLong yielded;

		CountingSource(AnswerStore inner, AtomicLong yielded) {
			this.inner = inner;
			this.yielded = yielded;
		}

		@Override
		public Iterable<Answer<Relation>> answers(Call<Relation> probe) {
			List<Answer<Relation>> out = new ArrayList<>();
			inner.answers(probe).forEach(row -> {
				yielded.incrementAndGet();
				out.add(row);
			});
			return out;
		}

		@Override
		public long estimate(Call<Relation> probe) {
			// pricing is exempt from the enumeration metric: it measures the SEARCH
			return inner.estimate(probe);
		}
	}

	private static AnswerSource chain(AtomicLong counter) {
		List<Literal> facts = new ArrayList<>();
		for (int i = 0; i < N; i++) {
			facts.add(parent(null, lval(i), lval(i + 1)));
		}
		return new CountingSource(AnswerStore.empty().asserting(facts), counter);
	}

	/** grandparent-of-39, deliberately mis-ordered: the unbound joins first. */
	private static org.clauseway.logic.goals.Goal misOrdered(AnswerSource db, Unifiable<Integer> gp) {
		Unifiable<Integer> p = lvar();
		return parent(db, gp, p)
				.and(parent(db, p, lval(N - 1)));
	}

	@Test
	public void plannedAnswersAreIdenticalAndEnumerateFarFewerFacts() {
		AtomicLong plain = new AtomicLong();
		Unifiable<Integer> gp1 = lvar();
		List<String> unplanned = Query.of(misOrdered(chain(plain), gp1)).solve(gp1)
				.map(Object::toString).collect(Collectors.toList());

		AtomicLong planned = new AtomicLong();
		Unifiable<Integer> gp2 = lvar();
		Optimizer pipeline = Optimizer.pipeline(new CascadingOptimizer(), new OrderingOptimizer());
		List<String> plannedAnswers = Query.of(misOrdered(chain(planned), gp2)).optimized(pipeline).solve(gp2)
				.map(Object::toString).collect(Collectors.toList());

		assertThat(plannedAnswers).isEqualTo(unplanned);
		assertThat(unplanned).containsExactly("{" + (N - 3) + "}");
		// unplanned: enumerate all N parent facts, then one indexed hit (N + 1);
		// planned: the bound lookup first — one bucket fact, then one more (2)
		assertThat(plain.get()).isGreaterThan((long) N);
		assertThat(planned.get() * 5).isLessThan(plain.get());
	}
}
