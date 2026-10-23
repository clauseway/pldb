package org.clauseway.pldb.relations;

// ABOUTME: A literal built from a row: it keeps that row and selects its answers back as ground rows of it.
// ABOUTME: Only a literal built this way can be selected, so the row's type rides from the builder to the read.

import static org.clauseway.logic.unification.terms.LVal.lval;

import java.util.Arrays;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.clauseway.logic.goals.Goal;
import org.clauseway.logic.solving.Query;
import org.clauseway.logic.solving.Row;
import org.clauseway.logic.unification.terms.Reified;
import org.clauseway.logic.unification.terms.Term;
import org.clauseway.logic.unification.terms.Unifiable;
import org.clauseway.pldb.AnswerProducer;
import org.clauseway.pldb.AnswerSource;

/**
 * A literal over a {@link Shape}'s row. The head is the row's columns
 * applied over its members; the row rides along, outside the literal's
 * identity, and {@link #select} rebuilds each answer into it. A plain
 * literal has no select: only a literal that knows its row can answer
 * as rows of it.
 */
public final class Rows<R extends Shape<R>> extends Literal {

	private final R row;

	private Rows(Literal head, R row) {
		super(head.getRel(), head.getArgs(), head.getReading());
		this.row = row;
	}

	/**
	 * The answers as ground rows: the row's members that are variables are
	 * selected, and each answer rebuilds the row with them bound. Refuses a
	 * wide cell — a row is ground, so a projected member, which never binds,
	 * cannot be selected.
	 */
	public Stream<R> select() {
		List<Term<?>> members = StreamSupport.stream(row.members().spliterator(), false)
				.collect(Collectors.<Term<?>> toList());
		Unifiable<?>[] holes = members.stream()
				.filter(member -> !member.isVal())
				.map(member -> (Unifiable<?>) member)
				.toArray(Unifiable<?>[]::new);
		if (holes.length == 0) {
			return Query.of(this).solve(lval(true)).map(answer -> row);
		}
		return Query.of(this).select(holes[0], Arrays.copyOfRange(holes, 1, holes.length)).rows()
				.map(answer -> row.withMembers(members.stream()
						.map(member -> member.isVal() ? member : bound(answer, member))
						.toArray(Term<?>[]::new)));
	}

	/** The answer's cell at a member, which must be ground: a row is a value, not a question. */
	private Term<?> bound(Row answer, Term<?> member) {
		Reified<?> cell = answer.get(member);
		if (!cell.isGround()) {
			throw new IllegalStateException(getRel().getName() + ": a wide cell at " + member
					+ " — select answers ground rows only");
		}
		return cell;
	}

	/** The builder after {@code rows(row)}: the names applied over the row's members, three ways to read. */
	public static final class Builder<R extends Shape<R>> {
		private final Literal.Builder head;
		private final R row;

		Builder(Literal.Builder head, R row) {
			this.head = head;
			this.row = row;
		}

		public Rows<R> from(AnswerSource source) {
			return new Rows<>(head.from(source), row);
		}

		public Rows<R> produced(AnswerProducer producer) {
			return new Rows<>(head.produced(producer), row);
		}

		/** A rule: the body is handed the row the head is built from, so both are over the same variables. */
		public Rows<R> solving(Function<R, Goal> body) {
			return new Rows<>(head.solving(body.apply(row)), row);
		}
	}
}
