package org.clauseway.pldb.relations;

// ABOUTME: The namespaced door: the class qualifies the name, so same-shaped
// ABOUTME: relations from two vocabularies stay distinct everywhere identity keys.

import org.clauseway.logic.solving.Answer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.clauseway.logic.unification.terms.LVar.lvar;

import java.util.Arrays;
import org.clauseway.logic.solving.Call;
import org.clauseway.logic.unification.terms.Any;
import org.clauseway.pldb.inmemory.AnswerStore;
import org.junit.Test;

public class NamespacedRelationTest {

	/** Two authors' vocabularies, same bare name, same shape. */
	private static final class Lending {
	}

	private static final class Billing {
	}

	private static Relation loan(Class<?> namespace) {
		return Literal.relation(namespace, "loan")
				.arg("member", lvar()).indexed()
				.arg("copy", lvar())
				.from(null)
				.getRel();
	}

	private static Call<Relation> everything(Relation relation) {
		return Call.of(relation, Answers.image(Any.of(0), Any.of(1)));
	}

	@Test
	public void theSameMintIsTheSameRelationAndNamespacesDivide() {
		assertThat(loan(Lending.class))
				.describedAs("two mints of one defining shape stay one relation")
				.isEqualTo(loan(Lending.class));
		assertThat(loan(Lending.class))
				.describedAs("the same shape under another namespace is another relation")
				.isNotEqualTo(loan(Billing.class));
		assertThat(loan(Lending.class).getName())
				.describedAs("the physical name stays bare — tables and mark rows never see the namespace")
				.isEqualTo("loan");
		assertThat(((RelationN) loan(Lending.class)).getNamespace())
				.describedAs("the FULL class name — package-distinct twins must divide too")
				.isEqualTo(Lending.class.getName());
	}

	@Test
	public void namespacedTwinsKeepSeparateExtensions() {
		// the silent-merge hazard made unrepresentable: same bare name, same
		// columns, two namespaces — the store keys them apart
		Answer<Relation> row = Answers.answer(loan(Lending.class),
				Arrays.asList((Object) "m1", "c1"));
		AnswerStore store = AnswerStore.empty()
				.with(loan(Lending.class), row);

		assertThat(store.answers(everything(loan(Lending.class)))).hasSize(1);
		assertThat(store.answers(everything(loan(Billing.class))))
				.describedAs("Billing's loan never sees Lending's rows")
				.isEmpty();
	}

}
