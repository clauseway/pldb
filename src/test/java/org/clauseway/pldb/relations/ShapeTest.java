package org.clauseway.pldb.relations;

// ABOUTME: A shape is a signed row with column names: a literal is built from it, remembers it, stays
// ABOUTME: equal to the same literal built by args, and selects its answers back as ground rows of the shape.

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.clauseway.logic.unification.terms.LVal.lval;
import static org.clauseway.logic.unification.terms.LVar.lvar;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import lombok.Value;
import org.clauseway.logic.goals.Goal;
import org.clauseway.logic.unification.terms.Term;
import org.clauseway.logic.unification.terms.Unifiable;
import org.clauseway.pldb.AnswerSource;
import org.clauseway.pldb.inmemory.AnswerStore;
import org.junit.Test;

public class ShapeTest {

	/** A point row: two columns, the first indexed. */
	@Value
	static class Point implements Shape<Point> {
		Unifiable<Integer> x;
		Unifiable<Integer> y;

		@Override
		public Iterable<? extends Term<?>> members() {
			return Arrays.asList(x, y);
		}

		@Override
		@SuppressWarnings("unchecked")
		public Point withMembers(Term<?>[] members) {
			return new Point((Unifiable<Integer>) members[0], (Unifiable<Integer>) members[1]);
		}

		@Override
		public List<Property<?>> columns() {
			return Arrays.asList(Property.of("x").indexed(), Property.of("y"));
		}

		static Point anyPoint() {
			return new Point(lvar(), lvar());
		}
	}

	private static Literal point(AnswerSource db, Point row) {
		return Literal.relation(ShapeTest.class, "point").columns(row).from(db);
	}

	private static AnswerStore twoPoints() {
		return AnswerStore.empty().asserting(
				point(null, new Point(lval(1), lval(2))),
				point(null, new Point(lval(3), lval(4))));
	}

	/** The relation and the arguments are what tabling and the stores key by; the remembered row touches neither. A literal's own equality is identity through its reading, with or without a row. */
	@Test
	public void aLiteralBuiltFromARowHasTheRelationAndArgsOfOneBuiltByArgs() {
		Unifiable<Integer> x = lvar();
		Unifiable<Integer> y = lvar();
		Literal byRow = point(null, new Point(x, y));
		Literal byArgs = Literal.relation(ShapeTest.class, "point")
				.arg("x", x).indexed()
				.arg("y", y)
				.from(null);
		assertThat(byRow.getRel()).isEqualTo(byArgs.getRel());
		assertThat(byRow.getRel().hashCode()).isEqualTo(byArgs.getRel().hashCode());
		assertThat(byRow.getArgs()).isEqualTo(byArgs.getArgs());
	}

	@Test
	public void selectAnswersGroundRowsOfTheShape() {
		List<Point> rows = point(twoPoints(), Point.anyPoint()).select(Point.class)
				.sorted((a, b) -> a.getX().get().compareTo(b.getX().get()))
				.collect(Collectors.toList());
		assertThat(rows).containsExactly(new Point(lval(1), lval(2)), new Point(lval(3), lval(4)));
	}

	@Test
	public void aValueMemberNarrowsTheQuestionAndRidesTheAnswer() {
		List<Point> rows = point(twoPoints(), new Point(lval(3), lvar())).select(Point.class)
				.collect(Collectors.toList());
		assertThat(rows).containsExactly(new Point(lval(3), lval(4)));
	}

	@Test
	public void selectRefusesALiteralBuiltWithoutAShape() {
		Literal byArgs = Literal.relation(ShapeTest.class, "point")
				.arg("x", lvar()).indexed()
				.arg("y", lvar())
				.from(twoPoints());
		assertThatThrownBy(() -> byArgs.select(Point.class))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("shape");
	}

	@Test
	public void selectRefusesAWideAnswer() {
		Literal anyPoint = Literal.relation(ShapeTest.class, "anyPoint").columns(Point.anyPoint()).solving(Goal.success());
		assertThatThrownBy(() -> anyPoint.select(Point.class).collect(Collectors.toList()))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("wide");
	}

	@Test
	public void aProbeProjectsEveryColumnAway() {
		Point probe = Point.anyPoint().probe();
		assertThat(probe.getX()).isInstanceOf(Projected.class);
		assertThat(probe.getY()).isInstanceOf(Projected.class);
		assertThat(point(null, probe).getRel().getArgs()).isEmpty();
	}
}
