package org.clauseway.pldb.relations.literals;

// ABOUTME: The write face by type: a literal over a source or a producer is External, with a fact to give and
// ABOUTME: a reading of a question's solutions as its facts; a literal a rule defines is not, so the write doors cannot take it.

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.clauseway.logic.nogoods.Exclusion.exclude;
import static org.clauseway.logic.unification.terms.LVal.lval;
import static org.clauseway.logic.unification.terms.LVar.lvar;
import static org.clauseway.pldb.relations.Projected.projected;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.clauseway.logic.goals.Goal;
import org.clauseway.logic.solving.Answer;
import org.clauseway.logic.solving.Condition;
import org.clauseway.logic.solving.Query;
import org.clauseway.logic.unification.terms.Unifiable;
import org.clauseway.pldb.AnswerProducer;
import org.clauseway.pldb.inmemory.AnswerStore;
import org.clauseway.pldb.relations.Answers;
import org.clauseway.pldb.relations.Relation;
import org.clauseway.pldb.relations.Schema;
import org.junit.Test;

public class ExternalTest {

	private static LiteralBuilder.Backend<Schema.Anonymous> person(Unifiable<Integer> id, Unifiable<String> name) {
		return Literal.relation(ExternalTest.class, "person")
				.arg("id", id).indexed()
				.arg("name", name);
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

	// -- the reading of solutions as facts ------------------------------------

	private static External<?> loan(Unifiable<Integer> id, Unifiable<String> copy) {
		return Literal.relation(ExternalTest.class, "loan")
				.arg("loanId", id).indexed()
				.arg("copy", copy)
				.from(null);
	}

	private static List<Answer<Relation>> facts(Goal question, External<?> template) {
		try (Stream<Answer<Relation>> rows = Query.of(question).select(template.facts()).stream()) {
			return rows.collect(Collectors.toList());
		}
	}

	private static String render(Answer<Relation> row) {
		return row.getRelation().getName() + Answers.values(row.getReified());
	}

	@Test
	public void everySolutionIsAFactOfTheTemplatesRelation() {
		Unifiable<Integer> id = lvar();
		Unifiable<String> copy = lvar();
		Goal question = copy.unifies("c1").and(id.unifies(1).or(id.unifies(2)));

		assertThat(facts(question, loan(id, copy)).stream().map(ExternalTest::render).sorted())
				.containsExactly("loan[1, c1]", "loan[2, c1]");
	}

	@Test
	public void aConstantTemplateCellRidesIntoTheFact() {
		Unifiable<Integer> id = lvar();

		assertThat(facts(id.unifies(7), loan(id, lval("archived"))).stream().map(ExternalTest::render))
				.containsExactly("loan[7, archived]");
	}

	@Test
	public void theFactsNameTheirCellsByColumn() {
		Unifiable<Integer> id = lvar();
		assertThat(loan(id, lvar()).facts().names()).containsExactly("loanId", "copy");
	}

	/** A fact has no place for a free cell: the solution is refused by column before it maps. */
	@Test
	public void aFreeTemplateCellRefusesAsNotGround() {
		Unifiable<Integer> id = lvar();
		Unifiable<String> copy = lvar();

		assertThatThrownBy(() -> facts(id.unifies(1), loan(id, copy)))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("not ground at [copy]");
	}

	@Test
	public void groundSolutionsAreUnconditionalFacts() {
		Unifiable<Integer> id = lvar();
		Unifiable<String> copy = lvar();
		Goal question = id.unifies(1).and(copy.unifies("c1")).and(exclude(copy.unifies("c9")));

		assertThat(facts(question, loan(id, copy)).stream().map(Answer::getCondition))
				.containsExactly(Condition.ONE);
	}

	@Test
	public void zeroSolutionsAreNoFacts() {
		Unifiable<Integer> id = lvar();
		assertThat(facts(id.unifies(1).and(id.unifies(2)), loan(id, lval("c1")))).isEmpty();
	}

	/** A projected() column leaves the relation; a template with one would land facts in a relation nobody stores. */
	@Test
	public void aTemplateWithAProjectedColumnHasNoFacts() {
		Unifiable<Integer> id = lvar();
		assertThatThrownBy(() -> loan(id, projected()).facts())
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("copy")
				.hasMessageContaining("projected");
	}
}
