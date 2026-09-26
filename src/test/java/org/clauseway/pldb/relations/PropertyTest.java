package org.clauseway.pldb.relations;

// ABOUTME: Property flags: ground() and indexed() are chainable metadata copies;
// ABOUTME: lookup identity stays the NAME, so flagged copies never break reads.

import org.clauseway.logic.solving.Answer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.clauseway.logic.unification.terms.LVar.lvar;

import java.util.Arrays;
import org.junit.Test;

public class PropertyTest {

	@Test
	public void flagsDefaultToFalse() {
		Property<Integer> id = Property.of("id");
		assertThat(id.isIndexed()).isFalse();
		assertThat(id.isGround()).isFalse();
	}

	@Test
	public void flagsChainInEitherOrderAndPreserveEachOther() {
		Property<Integer> a = Property.<Integer> of("id").indexed().ground();
		assertThat(a.isIndexed()).isTrue();
		assertThat(a.isGround()).isTrue();

		Property<Integer> b = Property.<Integer> of("id").ground().indexed();
		assertThat(b.isIndexed()).isTrue();
		assertThat(b.isGround()).isTrue();
	}

	@Test
	public void lookupIdentityIsTheNameRegardlessOfFlags() {
		Property<Integer> id = Property.of("id");
		Relation rel = Literal.relation(PropertyTest.class, "r")
				.arg("id", lvar()).indexed().ground()
				.arg("tag", lvar())
				.from(null).getRel();

		// the bare constant finds the flagged copy's column
		assertThat(rel.indexOf(id)).contains(0);
		Answer<Relation> fact = Answers.answer(rel, Arrays.asList(7, "a"));
		assertThat(Answers.get(fact, id)).contains(7);
	}

	@Test
	public void flaggedCopiesOfOneNameAreStillDuplicatesInARelation() {
		assertThatThrownBy(() -> Literal.relation(PropertyTest.class, "r")
				.arg("id", lvar()).indexed()
				.arg("id", lvar()).ground()
				.from(null))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("Duplicated");
	}
}
