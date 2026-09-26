package org.clauseway.pldb.transaction;

// ABOUTME: Pin equality is world identity: two reads of one world carry equal
// ABOUTME: pins, any commit between them makes them differ — never content compare.

import static org.assertj.core.api.Assertions.assertThat;
import static org.clauseway.logic.unification.terms.LVal.lval;

import java.util.Collections;
import org.assertj.core.api.Assertions;
import org.clauseway.logic.solving.Call;
import org.clauseway.logic.unification.terms.Any;
import org.clauseway.logic.unification.terms.LVar;
import org.clauseway.logic.unification.terms.Term;
import org.clauseway.logic.unification.terms.Unifiable;
import org.clauseway.pldb.inmemory.SharedDatabase;
import org.clauseway.pldb.relations.Answers;
import org.clauseway.pldb.relations.Literal;
import org.clauseway.pldb.relations.Relation;
import org.junit.Test;

public class PinWorldIdentityTest {

	private static Literal person(Unifiable<Integer> id, Unifiable<String> name) {
		return Literal.relation(PinWorldIdentityTest.class, "person")
				.arg("id", id).indexed()
				.arg("name", name)
				.from(null);
	}

	private static Call<Relation> probe() {
		Relation relation = person(LVar.lvar(), LVar.lvar()).getRel();
		Term<?>[] anys = new Term<?>[relation.getArgs().length];
		for (int i = 0; i < anys.length; i++) {
			anys[i] = Any.of(i);
		}
		return Call.of(relation, Answers.image(anys));
	}

	private static Literal loan(Unifiable<Integer> id, Unifiable<String> copy) {
		return Literal.relation(PinWorldIdentityTest.class, "loan")
				.arg("id", id).indexed()
				.arg("copy", copy)
				.from(null);
	}

	@Test
	public void aCommitElsewhereDoesNotDivideAnUntouchedRelationsPins() throws Exception {
		// the premise flow composes pins minted in DIFFERENT requests: a pin
		// names the RELATION's world, so a commit that never touched it must
		// leave later pins equal and the footprint union composable
		SharedDatabase shared = SharedDatabase.empty();
		Pin first = shared.open("a").read(probe()).getPin();

		assertThat(shared.open("mover").commit(Footprint.empty(),
				Collections.singletonList(loan(lval(1), lval("B")).fact()), Collections.emptyList())).isTrue();

		Pin second = shared.open("b").read(probe()).getPin();
		assertThat(second)
				.describedAs("person never moved — its pins still name one world")
				.isEqualTo(first);
		Footprint composed = Footprint.of(probe(), first)
				.union(Footprint.of(probe(), second));
		assertThat(composed.pins()).hasSize(1);

		assertThat(shared.open("mover2").commit(Footprint.empty(),
				Collections.singletonList(person(lval(2), lval("Grace")).fact()), Collections.emptyList())).isTrue();
		Pin third = shared.open("c").read(probe()).getPin();
		Assertions.assertThatThrownBy(() ->
						Footprint.of(probe(), first).union(Footprint.of(probe(), third)))
				.describedAs("person moved — composing across its worlds refuses")
				.isInstanceOf(Transaction.Conflict.class);
	}

	/** A stranger's relation wearing the same bare name, other namespace. */
	private static final class Elsewhere {
		private static Literal person(Unifiable<Integer> id, Unifiable<String> name) {
			return Literal.relation(Elsewhere.class, "person")
					.arg("id", id).indexed()
					.arg("name", name)
					.from(null);
		}
	}

	@Test
	public void aSameNamedRelationInAnotherNamespaceDoesNotDividePins() throws Exception {
		// the pin holds the RELATION, not its bare name: a commit to
		// Elsewhere.person must not move this namespace's person
		SharedDatabase shared = SharedDatabase.empty();
		Pin first = shared.open("a").read(probe()).getPin();

		assertThat(shared.open("mover").commit(Footprint.empty(),
				Collections.singletonList(Elsewhere.person(lval(1), lval("Imposter")).fact()), Collections.emptyList())).isTrue();

		assertThat(shared.open("b").read(probe()).getPin())
				.describedAs("the other namespace's person is a different relation — one world here")
				.isEqualTo(first);
	}

	@Test
	public void pinsOfOneWorldAreEqualAndACommitDividesThem() throws Exception {
		SharedDatabase shared = SharedDatabase.empty();
		Pin first = shared.open("a").read(probe()).getPin();
		Pin second = shared.open("b").read(probe()).getPin();
		assertThat(first)
				.describedAs("two snapshots of one world name the same world")
				.isEqualTo(second);

		SimulatedSerialization mover = shared.open("mover");
		assertThat(mover.commit(Footprint.of(Collections.emptyMap()),
				Collections.singletonList(person(lval(1), lval("Ada")).fact()), Collections.emptyList())).isTrue();

		Pin third = shared.open("c").read(probe()).getPin();
		assertThat(third)
				.describedAs("a commit between two pins makes them name different worlds")
				.isNotEqualTo(first);
	}
}
