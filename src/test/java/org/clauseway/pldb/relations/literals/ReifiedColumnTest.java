package org.clauseway.pldb.relations.literals;

// ABOUTME: A literal reified to a column: flag gives true where it answers and false
// ABOUTME: where it does not; option gives some(value) where it answers and none() where it does not.

import static org.assertj.core.api.Assertions.assertThat;
import static org.clauseway.logic.unification.terms.LVal.lval;
import static org.clauseway.logic.unification.terms.LVar.lvar;
import static org.clauseway.pldb.relations.literals.Literal.flag;
import static org.clauseway.pldb.relations.literals.Literal.option;
import static org.clauseway.pldb.relations.Projected.projected;

import java.util.stream.Collectors;
import org.clauseway.logic.solving.Query;
import org.clauseway.logic.unification.structures.LOption;
import org.clauseway.logic.unification.terms.Unifiable;
import org.clauseway.pldb.relations.Schema;

import org.clauseway.pldb.AnswerSource;
import org.clauseway.pldb.inmemory.AnswerStore;
import org.junit.Test;

public class ReifiedColumnTest {

	private static External<?> person(AnswerSource db, Unifiable<Integer> id, Unifiable<String> name) {
		return Literal.relation(ReifiedColumnTest.class, "person")
				.schema(Schema.builder()
						.arg("id", id).indexed()
						.arg("name", name)
						.build())
				.from(db);
	}

	private static AnswerStore db() {
		return AnswerStore.empty().asserting(
				person(null, lval(1), lval("Ada")),
				person(null, lval(2), lval("Ada")),
				person(null, lval(2), lval("Lovelace")));
	}

	@Test
	public void aFlagIsTrueWhereTheLiteralAnswers() {
		Unifiable<Boolean> known = lvar();
		assertThat(Query.of(flag(person(db(), lval(1), projected()), known)).solve(known)
				.map(Object::toString).collect(Collectors.toList()))
				.containsExactly("{true}");
	}

	@Test
	public void aFlagIsFalseWhereTheLiteralDoesNot() {
		Unifiable<Boolean> known = lvar();
		assertThat(Query.of(flag(person(db(), lval(9), projected()), known)).solve(known)
				.map(Object::toString).collect(Collectors.toList()))
				.containsExactly("{false}");
	}

	@Test
	public void anOptionIsSomeOfEachValueTheLiteralAnswers() {
		Unifiable<LOption<String>> name = lvar();
		assertThat(Query.of(option(n -> person(db(), lval(2), n), name)).solve(name)
				.map(Object::toString).collect(Collectors.toList()))
				.containsExactlyInAnyOrder("{Some({Ada})}", "{Some({Lovelace})}");
	}

	@Test
	public void anOptionIsNoneWhereTheLiteralDoesNot() {
		Unifiable<LOption<String>> name = lvar();
		assertThat(Query.of(option(n -> person(db(), lval(9), n), name)).solve(name)
				.map(Object::toString).collect(Collectors.toList()))
				.containsExactly("{None}");
	}

	/** The column is a head column: the rule's answers carry the reified value out. */
	@Test
	public void aReifiedColumnHeadsARule() {
		AnswerStore db = db();
		Unifiable<Integer> id = lvar();
		Unifiable<Boolean> known = lvar();
		Unifiable<LOption<String>> name = lvar();
		Literal<?> card = Literal.relation(ReifiedColumnTest.class, "card")
				.schema(Schema.builder()
						.arg("id", id)
						.arg("known", known)
						.arg("name", name)
						.build())
				.solving(flag(person(db, id, projected()), known)
						.and(option(n -> person(db, id, n), name)));
		assertThat(Query.of(id.unifies(1).and(card)).solve(known)
				.map(Object::toString).collect(Collectors.toList()))
				.containsExactly("{true}");
		assertThat(Query.of(id.unifies(1).and(card)).solve(name)
				.map(Object::toString).collect(Collectors.toList()))
				.containsExactly("{Some({Ada})}");
		assertThat(Query.of(id.unifies(9).and(card)).solve(known)
				.map(Object::toString).collect(Collectors.toList()))
				.containsExactly("{false}");
		assertThat(Query.of(id.unifies(9).and(card)).solve(name)
				.map(Object::toString).collect(Collectors.toList()))
				.containsExactly("{None}");
	}
}
