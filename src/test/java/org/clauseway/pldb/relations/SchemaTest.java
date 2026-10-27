package org.clauseway.pldb.relations;

// ABOUTME: A schema is a selector with column names: a literal is built from a row of it, typed by it, keeps
// ABOUTME: the relation of one built from an anonymous row of the same columns, and selects its values back.

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.clauseway.logic.unification.terms.LVal.lval;
import static org.clauseway.logic.unification.terms.LVar.lvar;
import static org.clauseway.pldb.relations.Projected.projected;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.Value;
import org.clauseway.pldb.relations.literals.External;

import org.clauseway.pldb.relations.literals.Literal;

import org.clauseway.logic.solving.Query;
import org.clauseway.logic.solving.Row;
import org.clauseway.logic.unification.terms.Unifiable;
import org.clauseway.pldb.AnswerSource;
import org.clauseway.pldb.inmemory.AnswerStore;
import org.junit.Test;

public class SchemaTest {

	/** A point as Java reads it. */
	@Value
	static class Coordinates {
		int x;
		int y;
	}

	/** A point row: two columns, the first indexed, mapped into coordinates. */
	@Value
	static class Point implements Schema<Coordinates> {
		Unifiable<Integer> x;
		Unifiable<Integer> y;

		@Override
		public Schema.Anonymous describe() {
			return Schema.builder().arg("x", x).indexed().arg("y", y).build();
		}

		@Override
		public Coordinates map(Row row) {
			return new Coordinates(row.get(x).get(), row.get(y).get());
		}

		static Point anyPoint() {
			return new Point(lvar(), lvar());
		}
	}

	private static External<Point> point(AnswerSource db, Point row) {
		return Literal.relation(SchemaTest.class, "point").schema(row).from(db);
	}

	/** The same relation over an anonymous row: the columns spelled by name, flags on the last one named. */
	private static External<Schema.Anonymous> point(AnswerSource db, Unifiable<Integer> x, Unifiable<Integer> y) {
		return Literal.relation(SchemaTest.class, "point")
				.schema(Schema.builder()
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

	private static <D> List<D> all(Stream<D> stream) {
		try (Stream<D> rows = stream) {
			return rows.collect(Collectors.toList());
		}
	}

	/** A typed row's keys and columns are read off its description, in its order. */
	@Test
	public void aTypedRowsKeysAndColumnsAreItsDescription() {
		Unifiable<Integer> x = lvar();
		Unifiable<Integer> y = lvar();
		Point row = new Point(x, y);
		List<Unifiable<?>> keys = new ArrayList<>();
		row.keys().forEach(keys::add);
		assertThat(keys).containsExactly(x, y);
		assertThat(row.columns()).containsExactly(Property.of("x").indexed(), Property.of("y"));
	}

	@Test
	public void anAnonymousRowNamesItsColumnsInOrderWithTheirFlags() {
		Unifiable<Integer> x = lvar();
		Unifiable<Integer> y = lvar();
		Schema.Anonymous row = Schema.builder().arg("x", x).indexed().arg("y", y).build();
		assertThat(row.columns()).containsExactly(Property.of("x").indexed(), Property.of("y"));
		assertThat(row.getKeys()).containsExactly(x, y);
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
	public void selectMapsEveryAnswerIntoTheSchemasValue() {
		Point any = Point.anyPoint();
		List<Coordinates> points = all(Query.of(point(twoPoints(), any)).select(any).stream());
		assertThat(points).containsExactlyInAnyOrder(new Coordinates(1, 2), new Coordinates(3, 4));
	}

	@Test
	public void selectOverAnAnonymousRowAnswersSolutions() {
		Unifiable<Integer> y = lvar();
		Literal<Schema.Anonymous> third = point(twoPoints(), lval(3), y);
		List<Row> rows = all(Query.of(third).select(third.getRow()).stream());
		assertThat(rows).hasSize(1);
		assertThat(rows.get(0).get(y).get()).isEqualTo(4);
	}

	@Test
	public void aValueMemberNarrowsTheQuestionAndRidesTheAnswer() {
		Point third = new Point(lval(3), lvar());
		assertThat(all(Query.of(point(twoPoints(), third)).select(third).stream()))
				.containsExactly(new Coordinates(3, 4));
	}

	/** The head is the row's columns over its members, so a body over the same row is over the head's variables. */
	@Test
	public void aRuleHeadAndItsBodyShareTheRow() {
		Point origin = Point.anyPoint();
		Literal<Point> atOrigin = Literal.relation(SchemaTest.class, "origin").schema(origin)
				.solving(origin.getX().unifies(0).and(origin.getY().unifies(0)));
		assertThat(all(Query.of(atOrigin).select(origin).stream())).containsExactly(new Coordinates(0, 0));
	}

	/** A Java value has no place for an open cell: the row is refused before the schema maps it, shown by column. */
	@Test
	public void aWideAnswerIsRefusedBeforeMapping() {
		Point any = Point.anyPoint();
		Literal<Point> anyPoint = Literal.relation(SchemaTest.class, "anyPoint").schema(any)
				.solving(any.getX().unifies(1));
		assertThatThrownBy(() -> all(Query.of(anyPoint).select(any).stream()))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("not ground at [y]")
				.hasMessageContaining("(x: {1}, y: _.");
	}

	/** A projected() member is a literal-definition marker; selected, it is an open cell like any other. */
	@Test
	public void aProjectedMemberSelectsAsAnOpenCell() {
		Point probe = new Point(lval(1), projected());
		assertThatThrownBy(() -> all(Query.of(point(twoPoints(), probe)).select(probe).stream()))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("not ground");
	}

	@Test
	public void aProbeProjectsEveryColumnAway() {
		Point probe = new Point(projected(), projected());
		assertThat(point(null, probe).getRelation().getProperties()).isEmpty();
	}
}
