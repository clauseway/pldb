package org.clauseway.pldb;

// ABOUTME: The overlay value: a frozen base plus a private staged delta — reads
// ABOUTME: union both, appends mint new values, ancestors and siblings stay true.

import org.clauseway.logic.solving.Query;
import static org.assertj.core.api.Assertions.assertThat;
import static org.clauseway.logic.nogoods.Exclusion.exclude;
import static org.clauseway.logic.unification.terms.LVal.lval;
import static org.clauseway.logic.unification.terms.LVar.lvar;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import org.clauseway.logic.unification.terms.Unifiable;
import org.clauseway.pldb.inmemory.AnswerStore;
import org.clauseway.pldb.relations.Literal;
import org.clauseway.pldb.transaction.WriteBuffer;
import org.junit.Test;

public class WriteBufferTest {

	private static Literal person(AnswerSource db, Unifiable<Long> id, Unifiable<String> name) {
		return Literal.relation(WriteBufferTest.class, "person").arg("id", id).indexed().arg("name", name).from(db);
	}

	private static AnswerSource base() {
		return AnswerStore.empty().asserting(Arrays.asList(
				person(null, lval(1L), lval("Ada")),
				person(null, lval(2L), lval("Alan"))));
	}

	private static List<String> ids(AnswerSource db) {
		Unifiable<Long> id = lvar();
		return Query.of(person(db, id, lvar())).solve(id)
				.map(Object::toString)
				.sorted()
				.collect(Collectors.toList());
	}

	@Test
	public void readsUnionTheBaseAndTheStagedDelta() {
		WriteBuffer lib = WriteBuffer.over(base())
				.asserting(Collections.singletonList(person(null, lval(3L), lval("Kurt")).fact()));
		assertThat(ids(lib)).containsExactly("{1}", "{2}", "{3}");
	}

	@Test
	public void anAncestorIsUndisturbedByADescendantsAppend() {
		WriteBuffer parent = WriteBuffer.over(base());
		parent.asserting(Collections.singletonList(person(null, lval(3L), lval("Kurt")).fact()));
		assertThat(ids(parent)).containsExactly("{1}", "{2}");
	}

	@Test
	public void siblingsForkIndependently() {
		WriteBuffer parent = WriteBuffer.over(base());
		WriteBuffer left = parent.asserting(Collections.singletonList(person(null, lval(3L), lval("Kurt")).fact()));
		WriteBuffer right = parent.asserting(Collections.singletonList(person(null, lval(4L), lval("Emmy")).fact()));
		assertThat(ids(left)).containsExactly("{1}", "{2}", "{3}");
		assertThat(ids(right)).containsExactly("{1}", "{2}", "{4}");
	}

	@Test
	public void stagedFactsKeepAppendOrder() {
		WriteBuffer lib = WriteBuffer.over(base())
				.asserting(Arrays.asList(person(null, lval(3L), lval("Kurt")).fact(), person(null, lval(4L), lval("Emmy")).fact()))
				.asserting(Collections.singletonList(person(null, lval(5L), lval("Noether")).fact()));
		assertThat(lib.stagedAssertions()).containsExactly(
				person(null, lval(3L), lval("Kurt")).fact(),
				person(null, lval(4L), lval("Emmy")).fact(),
				person(null, lval(5L), lval("Noether")).fact());
	}

	@Test
	public void aStagedDuplicateOfACommittedFactDeliversOnce() {
		WriteBuffer lib = WriteBuffer.over(base())
				.asserting(Collections.singletonList(person(null, lval(1L), lval("Ada")).fact()));
		assertThat(ids(lib)).containsExactly("{1}", "{2}");
	}

	@Test
	public void aFreshOverlayStagesNothing() {
		assertThat(WriteBuffer.over(base()).stagedAssertions()).isEmpty();
	}

	@Test
	public void negationSeesTheStagedDelta() {
		// the constraint tier reads through the same union: a staged row
		// entails a ground exclusion the base alone would have refuted
		WriteBuffer lib = WriteBuffer.over(base())
				.asserting(Collections.singletonList(person(null, lval(3L), lval("Kurt")).fact()));
		Unifiable<Long> free = lvar();
		assertThat(Query.of(free.unifies(3L)
				.and(exclude(person(lib, free, lval("Kurt"))))).solve(free)
				.collect(Collectors.toList())).isEmpty();
		assertThat(Query.of(free.unifies(3L)
				.and(exclude(person(WriteBuffer.over(base()), free, lval("Kurt"))))).solve(free)
				.map(Object::toString)
				.collect(Collectors.toList())).containsExactly("{3}");
	}
}
