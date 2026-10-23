package org.clauseway.pldb.relations;

// ABOUTME: A row is a signed value with column names: a literal built from it is a Rows of its type, keeps
// ABOUTME: the relation and args of one built by args, hands the row to a body, and selects ground rows back.

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

public class RowTest {

	/** A point row: two columns, the first indexed. */
	@Value
	static class Point implements Row<Point> {
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

	private static Rows<Point> point(AnswerSource db, Point row) {
		return Literal.relation(RowTest.class, "point").rows(row).from(db);
	}

	private static AnswerStore twoPoints() {
		return AnswerStore.empty().asserting(
				point(null, new Point(lval(1), lval(2))),
				point(null, new Point(lval(3), lval(4))));
	}

	/** The relation and the arguments are what tabling and the stores key by; the kept row touches neither. */
	@Test
	public void aLiteralBuiltFromARowHasTheRelationAndArgsOfOneBuiltByArgs() {
		Unifiable<Integer> x = lvar();
		Unifiable<Integer> y = lvar();
		Literal byRow = point(null, new Point(x, y));
		Literal byArgs = Literal.relation(RowTest.class, "point")
				.arg("x", x).indexed()
				.arg("y", y)
				.from(null);
		assertThat(byRow.getRel()).isEqualTo(byArgs.getRel());
		assertThat(byRow.getRel().hashCode()).isEqualTo(byArgs.getRel().hashCode());
		assertThat(byRow.getArgs()).isEqualTo(byArgs.getArgs());
	}

	@Test
	public void selectAnswersGroundRowsOfTheKind() {
		List<Point> rows = point(twoPoints(), Point.anyPoint()).select()
				.sorted((a, b) -> a.getX().get().compareTo(b.getX().get()))
				.collect(Collectors.toList());
		assertThat(rows).containsExactly(new Point(lval(1), lval(2)), new Point(lval(3), lval(4)));
	}

	@Test
	public void aValueMemberNarrowsTheQuestionAndRidesTheAnswer() {
		List<Point> rows = point(twoPoints(), new Point(lval(3), lvar())).select()
				.collect(Collectors.toList());
		assertThat(rows).containsExactly(new Point(lval(3), lval(4)));
	}

	/** The body receives the row the head is built from: both are over the same variables by construction. */
	@Test
	public void aRuleBodyIsHandedTheHeadsRow() {
		List<Point> rows = Literal.relation(RowTest.class, "origin").rows(Point.anyPoint())
				.solving(r -> r.getX().unifies(0).and(r.getY().unifies(0)))
				.select()
				.collect(Collectors.toList());
		assertThat(rows).containsExactly(new Point(lval(0), lval(0)));
	}

	@Test
	public void selectRefusesAWideAnswer() {
		Rows<Point> anyPoint = Literal.relation(RowTest.class, "anyPoint").rows(Point.anyPoint()).solving(r -> Goal.success());
		assertThatThrownBy(() -> anyPoint.select().collect(Collectors.toList()))
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
