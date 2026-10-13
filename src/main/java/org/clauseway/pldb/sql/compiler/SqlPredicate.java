package org.clauseway.pldb.sql.compiler;

// ABOUTME: One adapter-side predicate value: a WHERE fragment with its parameters
// ABOUTME: bound positionally — what a registered compiler produces from an atom.

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.Value;
import org.clauseway.vavr.collection.Array;

/**
 * The compiled form of one constraint atom, in the adapter's own vocabulary:
 * a SQL condition fragment plus the values it binds, joined into the fetch's
 * WHERE by conjunction. Values are always bound as parameters, never inlined
 * into the text. The vocabulary is deliberately flat — atomic conditions
 * under AND; the useful disjunction (a domain's value set) is {@link #in}.
 * Boolean nesting beyond the flat disjunction and arithmetic operands are
 * one deferred design decision (the AST); its reopening triggers are an
 * addo atom crossing a region, or a second SQL dialect forcing late
 * rendering. (Union domains and multi-literal nogoods, the original
 * triggers, are served by the flat or.)
 *
 * <p>NULL: SQL's three-valued logic makes every comparison silently drop
 * NULL rows — under-delivery, the one sin. A column holds NULL only when
 * it carries an optional codec (its values are {@code LOption}s), which a
 * compiler reads through {@code ColumnResolver#optional}: a comparison
 * over such a column must either stay local or carry {@link #isNull} as
 * a disjunct.
 */
@Value
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public class SqlPredicate {
	String fragment;
	Array<Object> parameters;

	/**
	 * Does this predicate select EXACTLY its atom's rows? Factories say yes;
	 * a compiler that approximates marks {@link #weakened()}. The bit guards
	 * the one composition the approximation direction forbids: a weakening
	 * selects MORE than the atom, so its complement selects LESS than the
	 * atom's complement — under-delivery — and {@link #negated()} refuses.
	 */
	boolean exact;

	public static <T> SqlPredicate in(String column, Iterable<T> values) {
		Array<Object> bound = Array.ofAll(StreamSupport.stream(values.spliterator(), false)
				.map(Object.class::cast));
		return new SqlPredicate(
				column + bound.map(v -> "?").mkString(" IN (", ", ", ")"),
				bound, true);
	}

	public static <T> SqlPredicate between(String column, T lo, T hi) {
		return new SqlPredicate(column + " BETWEEN ? AND ?", Array.of(lo, hi), true);
	}

	public static <T> SqlPredicate eq(String column, T value) {
		return new SqlPredicate(column + " = ?", Array.of(value), true);
	}

	public static SqlPredicate isNull(String column) {
		return new SqlPredicate(column + " IS NULL", Array.empty(), true);
	}

	public static SqlPredicate isNotNull(String column) {
		return new SqlPredicate(column + " IS NOT NULL", Array.empty(), true);
	}

	public static <T> SqlPredicate leq(String column, T value) {
		return new SqlPredicate(column + " <= ?", Array.of(value), true);
	}

	public static <T> SqlPredicate lss(String column, T value) {
		return new SqlPredicate(column + " < ?", Array.of(value), true);
	}

	public static <T> SqlPredicate geq(String column, T value) {
		return new SqlPredicate(column + " >= ?", Array.of(value), true);
	}

	public static <T> SqlPredicate gtr(String column, T value) {
		return new SqlPredicate(column + " > ?", Array.of(value), true);
	}

	public static <T> SqlPredicate neq(String column, T value) {
		return new SqlPredicate(column + " <> ?", Array.of(value), true);
	}

	/** Column against column — parameterless; a distinct name, since a String value would be ambiguous. */
	public static SqlPredicate eqColumns(String left, String right) {
		return new SqlPredicate(left + " = " + right, Array.empty(), true);
	}

	/** Column against column — parameterless; a distinct name, since a String value would be ambiguous. */
	public static SqlPredicate leqColumns(String less, String more) {
		return new SqlPredicate(less + " <= " + more, Array.empty(), true);
	}

	/** Column against column — parameterless; a distinct name, since a String value would be ambiguous. */
	public static SqlPredicate lssColumns(String less, String more) {
		return new SqlPredicate(less + " < " + more, Array.empty(), true);
	}

	/** Column against column — parameterless; a distinct name, since a String value would be ambiguous. */
	public static SqlPredicate neqColumns(String left, String right) {
		return new SqlPredicate(left + " <> " + right, Array.empty(), true);
	}

	/** The same selection, DECLARED wider than the atom — a compiler that approximates says so. */
	public SqlPredicate weakened() {
		return new SqlPredicate(fragment, parameters, false);
	}

	/** The complement — present only while exact; a weakening's complement under-delivers. */
	public Optional<SqlPredicate> negated() {
		return exact ?
				Optional.of(new SqlPredicate("NOT (" + fragment + ")", parameters, true)) :
				Optional.empty();
	}

	/**
	 * The disjunction, exact iff every part is. Disjunctions push WHOLE or
	 * not at all — dropping a disjunct strengthens, the forbidden direction —
	 * so callers assemble the full list or refuse; an empty or() has no
	 * meaning here and throws.
	 */
	public static SqlPredicate or(List<SqlPredicate> parts) {
		if (parts.isEmpty()) {
			throw new IllegalArgumentException("an empty disjunction has no rows to name");
		}
		return new SqlPredicate(
				parts.stream().map(SqlPredicate::getFragment)
						.collect(Collectors.joining(" OR ", "(", ")")),
				parts.stream().map(SqlPredicate::getParameters)
						.reduce(Array.empty(), Array::appendAll),
				parts.stream().allMatch(SqlPredicate::isExact));
	}

	/** The conjunction, exact iff every part is; a conjunct may drop freely (weaker). */
	public static SqlPredicate and(List<SqlPredicate> parts) {
		if (parts.isEmpty()) {
			throw new IllegalArgumentException("an empty conjunction claims nothing");
		}
		return new SqlPredicate(
				parts.stream().map(SqlPredicate::getFragment)
						.collect(Collectors.joining(" AND ", "(", ")")),
				parts.stream().map(SqlPredicate::getParameters)
						.reduce(Array.empty(), Array::appendAll),
				parts.stream().allMatch(SqlPredicate::isExact));
	}

	@Override
	public String toString() {
		return fragment + parameters.mkString(" [", ", ", "]");
	}
}
