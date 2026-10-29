package org.clauseway.pldb.inmemory;

// ABOUTME: In-memory option cells through the answers(Call) face: a free probe
// ABOUTME: delivers None and Some, none() selects, some(x) binds, indexed None keys.

import static org.assertj.core.api.Assertions.assertThat;
import static org.clauseway.logic.unification.structures.LOption.none;
import static org.clauseway.logic.unification.structures.LOption.some;
import static org.clauseway.logic.unification.terms.LVal.lval;
import static org.clauseway.logic.unification.terms.LVar.lvar;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import org.clauseway.logic.solving.Query;
import org.clauseway.logic.unification.structures.LOption;
import org.clauseway.logic.unification.terms.Unifiable;
import org.clauseway.pldb.AnswerSource;
import org.clauseway.pldb.relations.literals.External;
import org.clauseway.pldb.relations.literals.Literal;
import org.junit.Test;

public class OptionCellTest {

	private static External<?> person(AnswerSource db, Unifiable<Integer> id, Unifiable<LOption<String>> name) {
		return Literal.relation(OptionCellTest.class, "person")
				.arg("id", id).indexed()
				.arg("name", name)
				.from(db);
	}

	/** tag INDEXED: a None cell must key its bucket. */
	private static External<?> tagged(AnswerSource db, Unifiable<Integer> id, Unifiable<LOption<String>> tag) {
		return Literal.relation(OptionCellTest.class, "tagged")
				.arg("id", id).indexed()
				.arg("tag", tag).indexed()
				.from(db);
	}

	private static AnswerStore db() {
		return AnswerStore.empty().asserting(Arrays.asList(
				person(null, lval(1), some(lval("Ada"))),
				person(null, lval(2), none()),
				tagged(null, lval(1), some(lval("core"))),
				tagged(null, lval(2), none())));
	}

	@Test
	public void aFreeProbeDeliversBothShapes() {
		Unifiable<LOption<String>> name = lvar();
		List<String> names = Query.of(person(db(), lvar(), name)).solve(name)
				.map(Object::toString)
				.sorted()
				.collect(Collectors.toList());
		assertThat(names).containsExactly("{None}", "{Some({Ada})}");
	}

	@Test
	public void aNoneProbeSelectsOnlyTheAbsentRows() {
		Unifiable<Integer> id = lvar();
		assertThat(Query.of(person(db(), id, none())).solve(id)
				.map(Object::toString)
				.collect(Collectors.toList())).containsExactly("{2}");
	}

	@Test
	public void aSomeProbeBindsTheValueAndSkipsTheAbsentRows() {
		Unifiable<Integer> id = lvar();
		Unifiable<String> name = lvar();
		assertThat(Query.of(person(db(), id, some(name))).select(id, name).stream()
				.map(row -> row.get(id) + ":" + row.get(name))
				.collect(Collectors.toList())).containsExactly("1:Ada");
		assertThat(Query.of(person(db(), id, some(lval("Ada")))).solve(id)
				.map(Object::toString)
				.collect(Collectors.toList())).containsExactly("{1}");
	}

	@Test
	public void aNoneProbeOnAnIndexedColumnSelects() {
		Unifiable<Integer> id = lvar();
		assertThat(Query.of(tagged(db(), id, none())).solve(id)
				.map(Object::toString)
				.collect(Collectors.toList())).containsExactly("{2}");
		Unifiable<String> tag = lvar();
		assertThat(Query.of(tagged(db(), id, some(tag))).solve(tag)
				.map(Object::toString)
				.collect(Collectors.toList())).containsExactly("{core}");
	}
}
