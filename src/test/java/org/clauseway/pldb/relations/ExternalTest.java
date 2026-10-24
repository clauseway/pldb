package org.clauseway.pldb.relations;

// ABOUTME: The write face by type: a literal over a source or a producer is External and has a fact to give;
// ABOUTME: a literal a rule defines is not, so the write doors cannot take it.

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.clauseway.logic.unification.terms.LVal.lval;
import static org.clauseway.logic.unification.terms.LVar.lvar;

import org.clauseway.logic.goals.Goal;
import org.clauseway.logic.unification.terms.Unifiable;
import org.clauseway.pldb.AnswerProducer;
import org.clauseway.pldb.inmemory.AnswerStore;
import org.junit.Test;

public class ExternalTest {

	private static Literal.Builder<Schema.Anonymous> person(Unifiable<Integer> id, Unifiable<String> name) {
		return Literal.relation(ExternalTest.class, "person")
				.args(Schema.builder()
						.arg("id", id).indexed()
						.arg("name", name)
						.build());
	}

	@Test
	public void aLiteralOverASourceIsExternal() {
		External<Schema.Anonymous> stored = person(lval(1), lval("Ada")).from(AnswerStore.empty());
		assertThat(stored.fact().getRelation().getName()).isEqualTo("person");
	}

	@Test
	public void aLiteralOverAProducerIsExternal() {
		External<Schema.Anonymous> streamed = person(lval(1), lval("Ada")).produced(AnswerProducer.of(AnswerStore.empty()));
		assertThat(streamed.fact().getRelation().getName()).isEqualTo("person");
	}

	@Test
	public void aLiteralARuleDefinesIsNot() {
		Literal<Schema.Anonymous> derived = person(lval(1), lval("Ada")).solving(Goal.success());
		assertThat(derived).isNotInstanceOf(External.class);
	}

	@Test
	public void aFactNeedsEveryColumnGround() {
		assertThatThrownBy(() -> person(lval(1), lvar()).from(AnswerStore.empty()).fact())
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("name");
	}
}
