package org.clauseway.pldb.relations;

// ABOUTME: Receipts for the row image: tuple-backed construction and decode, and
// ABOUTME: delivery through the engine's one restate — coupled anys mint once.

import org.clauseway.logic.solving.Query;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.clauseway.logic.unification.terms.LVal.lval;
import static org.clauseway.logic.unification.terms.LVar.lvar;

import java.util.Arrays;
import java.util.stream.Collectors;
import org.clauseway.functional.tuples.Tuple;
import org.clauseway.logic.constraints.Constraints;
import org.clauseway.logic.goals.Goal;
import org.clauseway.logic.solving.Residues;
import org.clauseway.logic.unification.terms.Any;
import org.clauseway.logic.unification.terms.Reified;
import org.clauseway.logic.unification.terms.Term;
import org.clauseway.logic.unification.terms.Unifiable;
import org.junit.Test;

public class RowImageTest {

	private static Unifiable<Object> anchor(Unifiable<?>... cells) {
		return lval(Tuple.ofAll(Arrays.stream(cells)
				.map(Unifiable::getObjectTerm)
				.toArray())).getObjectUnifiable();
	}

	@Test
	public void aGroundRowBindsFreeAnchorCells() {
		Unifiable<String> x = lvar();
		Unifiable<String> y = lvar();
		Reified<?> image = Answers.image(lval("m1"), lval("c3"));

		Goal g = Residues.restate(image, Residues.TRUE, anchor(x, y));

		assertThat(Query.of(g).solve(x).map(Term::get).collect(Collectors.toList()))
				.containsExactly("m1");
		assertThat(Query.of(g).solve(y).map(Term::get).collect(Collectors.toList()))
				.containsExactly("c3");
	}

	@Test
	public void aGroundMismatchRefuses() {
		Unifiable<String> x = lvar();
		Reified<?> image = Answers.image(lval("m1"), lval("c3"));

		assertThat(Query.of(Residues.restate(image, Residues.TRUE, anchor(x, lval("c9")))).solve(x).count()).isZero();
	}

	@Test
	public void anArityMismatchRefuses() {
		Unifiable<String> x = lvar();
		Reified<?> image = Answers.image(lval("m1"));

		assertThat(Query.of(Residues.restate(image, Residues.TRUE, anchor(x, lvar()))).solve(x).count()).isZero();
	}

	@Test
	public void coupledAnysMintOneVariable() {
		Unifiable<Integer> x = lvar();
		Unifiable<Integer> y = lvar();
		Reified<?> image = Answers.image(Any.of(0), Any.of(0));

		Goal g = Residues.restate(image, Residues.TRUE, anchor(x, y))
				.and(Constraints.unify(x, lval(5)));

		assertThat(Query.of(g).solve(y).map(Term::get).collect(Collectors.toList()))
				.containsExactly(5);
	}

	@Test
	public void distinctAnysStayFree() {
		Unifiable<Integer> x = lvar();
		Unifiable<Integer> y = lvar();
		Reified<?> image = Answers.image(Any.of(0), Any.of(1));

		Goal g = Residues.restate(image, Residues.TRUE, anchor(x, y))
				.and(Constraints.unify(x, lval(5)))
				.and(Constraints.unify(y, lval(7)));

		assertThat(Query.of(g).solve(y).map(Term::get).collect(Collectors.toList()))
				.containsExactly(7);
	}

	@Test
	public void positionsRoundTripsAndRefusesNonRows() {
		Reified<?> image = Answers.image(lval("m1"), Any.of(0));
		assertThat(Answers.positions(image)).hasSize(2);
		assertThat(Answers.positions(image).get(0).get()).isEqualTo("m1");
		assertThat(Answers.positions(image).get(1)).isEqualTo(Any.of(0));

		assertThatThrownBy(() -> Answers.positions((Reified<?>) lval("scalar")))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("not a row image");
	}
}
