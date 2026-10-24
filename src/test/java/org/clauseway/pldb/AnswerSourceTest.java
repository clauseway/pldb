package org.clauseway.pldb;

// ABOUTME: Pins the AnswerSource seam: lookups and posted constraints constructed
// ABOUTME: against the read face answer identically to the AnswerStore-typed path.

import org.clauseway.logic.solving.Query;
import static org.assertj.core.api.Assertions.assertThat;
import static org.clauseway.logic.unification.terms.LVal.lval;
import static org.clauseway.logic.unification.terms.LVar.lvar;

import java.util.Arrays;
import java.util.stream.Collectors;
import org.clauseway.logic.unification.terms.Unifiable;
import org.clauseway.pldb.inmemory.AnswerStore;
import org.clauseway.pldb.relations.literals.External;
import org.clauseway.pldb.relations.literals.Literal;
import org.clauseway.pldb.relations.Schema;
import org.clauseway.pldb.relations.Property;
import org.clauseway.pldb.relations.Relation;
import org.junit.Test;

public class AnswerSourceTest {

	private static External<?> person(AnswerSource db, Unifiable<Integer> id, Unifiable<String> name) {
		return Literal.relation(AnswerSourceTest.class, "person")
				.args(Schema.builder()
						.arg("id", id).indexed()
						.arg("name", name)
						.build())
				.from(db);
	}

	private static Relation personRel() {
		return person(null, lvar(), lvar()).getRelation();
	}

	private static final Property<Integer> id = Property.of("id");
	private static final Property<String> name = Property.of("name");

	private static final AnswerStore db = AnswerStore.empty()
			.asserting(Arrays.asList(
					person(null, lval(1), lval("Ada")),
					person(null, lval(2), lval("Alan")),
					person(null, lval(3), lval("Kurt"))));

	@Test
	public void aLookupThroughTheSeamAnswersLikeTheDatabase() {
		AnswerSource source = db;

		Unifiable<String> viaSource = lvar();
		Unifiable<String> viaDb = lvar();
		assertThat(Query.of(person(source, lvar(), viaSource)).solve(viaSource)
				.map(Object::toString)
				.collect(Collectors.toList()))
				.containsExactlyElementsOf(Query.of(person(db, lvar(), viaDb)).solve(viaDb)
						.map(Object::toString)
						.collect(Collectors.toList()));
	}

	@Test
	public void aPostedConstraintThroughTheSeamAnswersLikeTheDatabase() {
		AnswerSource source = db;

		Unifiable<Integer> keyViaSource = lvar();
		Unifiable<Integer> keyViaDb = lvar();
		assertThat(Query.of(person(source, keyViaSource, lvar()).posted()).solve(keyViaSource)
				.map(Object::toString)
				.collect(Collectors.toList()))
				.containsExactlyElementsOf(Query.of(person(db, keyViaDb, lvar()).posted()).solve(keyViaDb)
						.map(Object::toString)
						.collect(Collectors.toList()));
	}
}
