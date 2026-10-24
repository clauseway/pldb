package org.clauseway.pldb.relations;

// ABOUTME: A schema is a signed value with column names: a literal is built from a row of it, typed by it, keeps
// ABOUTME: the relation of one built from an anonymous row of the same columns, and selects ground rows back.

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.clauseway.logic.unification.terms.LVal.lval;
import static org.clauseway.logic.unification.terms.LVar.lvar;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import lombok.Value;
import org.clauseway.pldb.relations.literals.External;

import org.clauseway.pldb.relations.literals.Literal;
import org.clauseway.pldb.relations.literals.Question;

import org.clauseway.logic.goals.Goal;
import org.clauseway.logic.solving.Query;
import org.clauseway.logic.unification.terms.Term;
import org.clauseway.logic.unification.terms.Unifiable;
import org.clauseway.pldb.AnswerSource;
import org.clauseway.pldb.inmemory.AnswerStore;
import org.junit.Test;

public class SchemaTest {

	/** A point row: two columns, the first indexed. */
	@Value
	static class Point implements Schema<Point> {
		Term<Integer> x;
		Term<Integer> y;

		@Override
		public Iterable<? extends Term<?>> members() {
			return Arrays.asList(x, y);
		}

		@Override
		@SuppressWarnings("unchecked")
		public Point withMembers(Term<?>[] members) {
			return new Point((Term<Integer>) members[0], (Term<Integer>) members[1]);
		}

		@Override
		public List<Property<?>> columns() {
			return Arrays.asList(Property.of("x").indexed(), Property.of("y"));
		}

		static Point anyPoint() {
			return new Point(lvar(), lvar());
		}
	}

	private static External<Point> point(AnswerSource db, Point row) {
		return Literal.relation(SchemaTest.class, "point").args(row).from(db);
	}

	/** The same relation over an anonymous row: the columns spelled by name, flags on the last one named. */
	private static External<Schema.Anonymous> point(AnswerSource db, Unifiable<Integer> x, Unifiable<Integer> y) {
		return Literal.relation(SchemaTest.class, "point")
				.args(Schema.builder()
								.arg("x", x).indexed()
								.arg("y", y)
								.build())
				.from(db);
	}

	private static AnswerStore twoPoints() {
		return AnswerStore.empty().asserting(
				point(null, new Point(lval(1), lval(2))),
				point(null, new Point(lval(3), lval(4))));
	}

	@Test
	public void anAnonymousRowNamesItsColumnsInOrderWithTheirFlags() {
		Unifiable<Integer> x = lvar();
		Unifiable<Integer> y = lvar();
		Schema.Anonymous row = Schema.builder().arg("x", x).indexed().arg("y", y).build();
		assertThat(row.columns()).containsExactly(Property.of("x").indexed(), Property.of("y"));
		assertThat(row.getMembers()).containsExactly(x, y);
	}

	@Test
	public void aFlagBeforeAnyColumnRefuses() {
		assertThatThrownBy(() -> Schema.builder().indexed())
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("column");
	}

	/** The relation and the arguments are what tabling and the stores key by; the row's type touches neither. */
	@Test
	public void aLiteralOverATypedRowHasTheRelationAndArgsOfOneOverAnAnonymousRow() {
		Unifiable<Integer> x = lvar();
		Unifiable<Integer> y = lvar();
		Literal<Point> typed = point(null, new Point(x, y));
		Literal<Schema.Anonymous> anonymous = point(null, x, y);
		assertThat(typed.getRelation()).isEqualTo(anonymous.getRelation());
		assertThat(typed.getRelation().hashCode()).isEqualTo(anonymous.getRelation().hashCode());
		assertThat(typed.getArgs()).isEqualTo(anonymous.getArgs());
	}

	@Test
	public void selectAnswersGroundRowsOfTheKind() {
		Point any = Point.anyPoint();
		List<Point> rows = Question.select(Query.of(point(twoPoints(), any)), any)
				.sorted((a, b) -> a.getX().get().compareTo(b.getX().get()))
				.collect(Collectors.toList());
		assertThat(rows).containsExactly(new Point(lval(1), lval(2)), new Point(lval(3), lval(4)));
	}

	@Test
	public void selectOverAnAnonymousRowAnswersAnonymousRows() {
		Literal<Schema.Anonymous> third = point(twoPoints(), lval(3), lvar());
		List<Schema.Anonymous> rows = Question.select(Query.of(third), third.getRow())
				.collect(Collectors.toList());
		assertThat(rows).hasSize(1);
		assertThat(rows.get(0).getMembers()).containsExactly(lval(3), lval(4));
	}

	@Test
	public void aValueMemberNarrowsTheQuestionAndRidesTheAnswer() {
		Point third = new Point(lval(3), lvar());
		List<Point> rows = Question.select(Query.of(point(twoPoints(), third)), third)
				.collect(Collectors.toList());
		assertThat(rows).containsExactly(new Point(lval(3), lval(4)));
	}

	/** The head is the row's columns over its members, so a body over the same row is over the head's variables. */
	@Test
	public void aRuleHeadAndItsBodyShareTheRow() {
		Point origin = Point.anyPoint();
		List<Point> rows = Question.select(Query.of(Literal.relation(SchemaTest.class, "origin").args(origin)
				.solving(origin.getX().unifiable().unifies(0).and(origin.getY().unifiable().unifies(0)))), origin)
				.collect(Collectors.toList());
		assertThat(rows).containsExactly(new Point(lval(0), lval(0)));
	}

	/** A free cell comes back as a fresh variable: the answer is a pattern again, and the Java boundary decides. */
	@Test
	public void aWideAnswerSelectsAsAPattern() {
		Point any = Point.anyPoint();
		Literal<Point> anyPoint = Literal.relation(SchemaTest.class, "anyPoint").args(any).solving(any.getX().unifiable().unifies(1));
		List<Point> rows = Question.select(Query.of(anyPoint), any).collect(Collectors.toList());
		assertThat(rows).hasSize(1);
		assertThat(rows.get(0).getX()).isEqualTo(lval(1));
		assertThat(rows.get(0).getY().asVar()).isPresent();
		assertThat(rows.get(0).getY()).isNotSameAs(any.getY());
	}

	@Test
	public void aProbeProjectsEveryColumnAway() {
		Point probe = Point.anyPoint().probe();
		assertThat(probe.getX()).isInstanceOf(Projected.class);
		assertThat(probe.getY()).isInstanceOf(Projected.class);
		assertThat(point(null, probe).getRelation().getProperties()).isEmpty();
	}
}
