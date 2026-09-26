package org.clauseway.pldb.transaction;

// ABOUTME: The composite pin's algebra: singletons lift, agreeing unions merge,
// ABOUTME: a region read at two pins refuses — staleness is structural, not silent.

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.clauseway.logic.unification.terms.LVar.lvar;

import lombok.Value;
import org.clauseway.logic.solving.Call;
import org.clauseway.logic.unification.terms.Any;
import org.clauseway.logic.unification.terms.Term;
import org.clauseway.pldb.relations.Answers;
import org.clauseway.pldb.relations.Literal;
import org.clauseway.pldb.relations.Relation;
import org.junit.Test;

public class FootprintTest {

	@Value
	private static class Generation implements Pin {
		int generation;
	}

	private static Call<Relation> region(String relation) {
		Relation rel = Literal.relation(FootprintTest.class, relation)
				.arg("id", lvar())
				.arg("value", lvar())
				.from(null)
				.getRel();
		Term<?>[] anys = new Term<?>[rel.getArgs().length];
		for (int i = 0; i < anys.length; i++) {
			anys[i] = Any.of(i);
		}
		return Call.of(rel, Answers.image(anys));
	}

	@Test
	public void aFootprintIsAPinAndSingletonsLiftLeaves() throws Exception {
		Footprint singleton = Footprint.of(region("person"), new Generation(1));
		assertThat(singleton).isInstanceOf(Pin.class);
		assertThat(Pinned.of("answers", singleton).getPin())
				.describedAs("a derived read completes with a composite pin")
				.isEqualTo(singleton);
	}

	@Test
	public void agreeingUnionsMergeAndDuplicateRegionsFold() throws Exception {
		Footprint left = Footprint.of(region("person"), new Generation(1))
				.union(Footprint.of(region("book"), new Generation(4)));
		Footprint right = Footprint.of(region("person"), new Generation(1))
				.union(Footprint.of(region("loan"), new Generation(2)));

		Footprint union = left.union(right);
		assertThat(union.pins()).hasSize(3);
		assertThat(union.pins().get(region("person"))).isEqualTo(new Generation(1));
	}

	@Test
	public void aRegionReadAtTwoPinsRefusesTheUnion() throws Exception {
		// the wire-face claim at unit grain: composing a part that read
		// person at world 1 with a part that read it at world 2 is a
		// STRUCTURAL conflict — never a silently stale answer
		Footprint atOne = Footprint.of(region("person"), new Generation(1));
		Footprint atTwo = Footprint.of(region("person"), new Generation(2));
		assertThatThrownBy(() -> atOne.union(atTwo))
				.isInstanceOf(Transaction.Conflict.class)
				.hasMessageContaining("person")
				.hasMessageContaining("different");
	}

	@Test
	public void emptyIsTheUnionIdentity() throws Exception {
		Footprint some = Footprint.of(region("person"), new Generation(1));
		assertThat(Footprint.empty().union(some)).isEqualTo(some);
		assertThat(some.union(Footprint.empty())).isEqualTo(some);
	}
}
