package org.clauseway.pldb.relations;

// ABOUTME: The answer codec at the seam: a Fact encodes as its ground reified row
// ABOUTME: at ONE; images decode per position into cells or values.

import org.clauseway.logic.solving.Answer;
import static org.clauseway.logic.unification.terms.LVal.lval;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.clauseway.functional.tuples.Tuple;
import org.clauseway.logic.solving.Condition;
import org.clauseway.logic.unification.terms.Reified;
import org.clauseway.logic.unification.terms.Term;
import org.clauseway.vavr.control.Try;

/**
 * The seam's value codec, written once. An answer is the cell's entry
 * shape — (reified row, {@link Condition}) — and a {@link Fact} is the
 * ground corner: its row reified whole, conditioned {@link Condition#ONE}.
 * Decoding reads POSITIONALLY through the tuple's structural contract,
 * never through rendering: a reified term's toString decorates, the
 * codec hands back the values.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class Answers {

	/** A row image: cells flat on the tuple family — natively structural. */
	public static Reified<?> image(Term<?>... cells) {
		return (Reified<?>) lval(Tuple.ofAll((Object[]) cells));
	}

	/** {@link #image(Term[])} over a collected cell sequence. */
	public static Reified<?> image(List<? extends Term<?>> cells) {
		return (Reified<?>) lval(Tuple.ofAll(cells.toArray()));
	}

	/** A ground row as the answer shape: values reified, conditioned ONE. */
	public static Answer<Relation> answer(Relation relation, List<?> values) {
		return Answer.of(relation, image(values.stream()
				.map(v -> (Term<?>) lval(v))
				.collect(Collectors.toList())), Condition.ONE);
	}

	/**
	 * Whether {@code wide} SUBSUMES {@code narrow}: every instance the
	 * narrow row claims is already claimed by the wide one, under a
	 * condition at least as permissive — the licence to DROP the narrow
	 * from a delivery. Dropping is the under-delivery direction, so both
	 * checks are strict: the wide's frees must bind CONSISTENTLY (a
	 * coupled {@code (x,x)} never subsumes {@code (1,2)}), a wide ground
	 * cell must equal a ground narrow cell (a free narrow cell claims
	 * more), and the wide's condition must ABSORB the narrow's (a guarded
	 * wide never swallows an unconditional row).
	 */
	public static boolean subsumes(Answer<Relation> wide, Answer<Relation> narrow) {
		List<Term<Object>> w = positions(wide.getReified());
		List<Term<Object>> n = positions(narrow.getReified());
		if (w.size() != n.size()) {
			return false;
		}
		if (!narrow.getCondition().absorbedBy(wide.getCondition())) {
			return false;
		}
		Map<Term<Object>, Term<Object>> binding = new HashMap<>();
		for (int i = 0; i < w.size(); i++) {
			Term<Object> wc = w.get(i);
			Term<Object> nc = n.get(i);
			if (wc.isVal()) {
				if (!nc.isVal() || !Objects.equals(wc.get(), nc.get())) {
					return false;
				}
			} else {
				Term<Object> bound = binding.putIfAbsent(wc, nc);
				if (bound != null && !bound.equals(nc)) {
					return false;
				}
			}
		}
		return true;
	}

	/**
	 * The write doors' claim check: a row lands whole and decided — every
	 * cell ground, the condition {@link Condition#ONE}. A wide cell
	 * refuses by relation and column; a guarded row refuses toward the
	 * explicit choice ({@link Answer#unconditional()}).
	 */
	public static Answer<Relation> landable(Answer<Relation> row) {
		List<Term<Object>> cells = positions(row.getReified());
		for (int i = 0; i < cells.size(); i++) {
			if (!cells.get(i).isVal()) {
				throw new IllegalStateException(row.getRelation().getName() + "."
						+ row.getRelation().getArgs()[i].getName()
						+ " is not ground — a write lands whole rows only");
			}
		}
		if (!Condition.ONE.equals(row.getCondition())) {
			throw new IllegalStateException(row.getRelation().getName()
					+ ": a guarded row cannot land — decide it, or drop the guard"
					+ " explicitly (unconditional())");
		}
		return row;
	}

	/** The row's raw values, positional. The row must be fully ground. */
	public static List<Object> values(Reified<?> row) {
		return positions(row).stream().map(Term::get).collect(Collectors.toList());
	}

	/** The named column's value — empty for an unknown column or a wide cell. */
	@SuppressWarnings("unchecked")
	public static <T> Optional<T> get(Answer<Relation> answer, Property<T> property) {
		return answer.getRelation().indexOf(property)
				.map(i -> positions(answer.getReified()).get(i))
				.filter(cell -> cell.isVal())
				.flatMap(cell -> Try.of(() -> (T) ((Term<Object>) cell).get())
						.toJavaOptional());
	}

	/**
	 * The image's cells in Term vocabulary — a ground position is a value
	 * ({@code asVal}), a free position an any ({@code asReified}) — the same
	 * vocabulary as the walked terms a row is compared against and the
	 * database index keys by, coupling identity preserved. The codec owns
	 * the representation: cells are read through the structural contract.
	 */
	@SuppressWarnings("unchecked")
	public static List<Term<Object>> positions(Reified<?> image) {
		Object w = image.get();
		if (!(w instanceof Tuple)) {
			throw new IllegalArgumentException("not a row image: " + image);
		}
		Tuple row = (Tuple) w;
		return IntStream.rangeClosed(1, row.arity())
				.mapToObj(i -> (Term<Object>) row.get(i))
				.collect(Collectors.toList());
	}
}
