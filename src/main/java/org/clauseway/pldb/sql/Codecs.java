package org.clauseway.pldb.sql;

// ABOUTME: The per-source codec map: builtins pass through, column codecs bind
// ABOUTME: through a template literal, the unknown refuses loudly on the write side.

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.HashMap;
import java.util.Map;
import org.clauseway.logic.unification.terms.Unifiable;
import org.clauseway.pldb.relations.literals.Literal;
import org.clauseway.pldb.relations.Property;
import org.clauseway.pldb.relations.Relation;
import org.clauseway.vavr.collection.Array;

/**
 * The value↔wire translation table. Serialization is the BACKEND's
 * concern: the boundary declaration ({@code Literal}/{@code Relation})
 * says nothing about storage, so codecs live here, on the source, keyed
 * by COLUMN — the same Java type may encode differently in different
 * columns. {@link #withCodec} binds them through a template literal: the
 * relation's defining method is the address book, {@link Codec#arg()}
 * markers the payload, and the signature type-checks the association.
 * WRITES are strict — a value with neither a column codec nor a builtin
 * refuses by relation and column (and a structural value keeps the
 * modelling refusal: its relational spelling is a child relation). READS
 * are lenient — a column without a codec passes its cells through
 * unchanged, because the backend's types are the backend's business and
 * an opaque value still unifies as itself.
 */
public final class Codecs {

	private final Map<Class<?>, Codec<?>> builtins = new HashMap<>();
	private final Map<String, Map<String, Codec<?>>> byColumn = new HashMap<>();

	private Codecs() {
	}

	/** Identity codecs for the JDBC-representable builtins. */
	public static Codecs builtin() {
		Codecs codecs = new Codecs();
		for (Class<?> type : new Class<?>[]{
				Integer.class, Long.class, Short.class, Byte.class,
				Double.class, Float.class, BigDecimal.class, BigInteger.class,
				Boolean.class, Character.class, String.class}) {
			codecs.identity(type);
		}
		return codecs;
	}

	@SuppressWarnings("unchecked")
	private <T> void identity(Class<T> type) {
		builtins.put(type, Codec.of(type, (Class<Object>) type, value -> value, cell -> (T) cell));
	}

	/**
	 * Binds column codecs through a template literal: every
	 * {@link Codec#arg()} marker in the args binds its codec to the column
	 * at its position. A markerless template refuses — it registers
	 * nothing. A doubly-bound column refuses.
	 */
	public Codecs withCodec(Literal<?> template) {
		Relation relation = template.getRel();
		Property<?>[] columns = relation.getArgs();
		Array<Unifiable<?>> args = template.getArgs();
		boolean bound = false;
		for (int i = 0; i < columns.length; i++) {
			if (!(args.get(i) instanceof Codec.Arg)) {
				continue;
			}
			Codec<?> codec = ((Codec.Arg<?>) args.get(i)).getCodec();
			Map<String, Codec<?>> perColumn = byColumn.computeIfAbsent(relation.getName(), name -> new HashMap<>());
			if (perColumn.putIfAbsent(columns[i].getName(), codec) != null) {
				throw new IllegalStateException(relation.getName() + ": column '"
						+ columns[i].getName() + "' already has a codec");
			}
			bound = true;
		}
		if (!bound) {
			throw new IllegalStateException(relation.getName()
					+ ": the template carries no Codec.arg() marker — nothing to bind");
		}
		return this;
	}

	/** The write side: strict — column codec, then builtin, then refuse by relation and column. */
	Object encode(Relation relation, Property<?> column, Object value) {
		Codec<?> codec = columnCodec(relation, column);
		if (codec == null) {
			codec = builtins.get(value.getClass());
		}
		if (codec != null) {
			return codec.encode(value);
		}
		if (value instanceof Iterable || value instanceof Map || value.getClass().isArray()) {
			throw new IllegalStateException("flush of " + relation.getName()
					+ ": column '" + column.getName() + "' holds a structural value ("
					+ value.getClass().getSimpleName()
					+ ") — a first-normal-form violation; model it as a child relation");
		}
		throw new IllegalStateException("flush of " + relation.getName()
				+ ": column '" + column.getName() + "' holds a "
				+ value.getClass().getSimpleName()
				+ " and no codec is registered for it");
	}

	/**
	 * The read side: lenient — a column without a codec passes its cells
	 * through — except for NULL, which only an optional codec may read:
	 * anywhere else it refuses by relation and column.
	 */
	Object decode(Relation relation, Property<?> column, Object cell) {
		Codec<?> codec = columnCodec(relation, column);
		if (cell == null && (codec == null || !codec.isAcceptsNull())) {
			throw new IllegalStateException(relation.getName() + ": column '" + column.getName()
					+ "' holds null and has no optional codec — declare one with Codec.optional(...)");
		}
		return codec == null ? cell : codec.decode(cell);
	}

	/** Whether the column's cells are options — it carries an optional codec. */
	boolean optional(Relation relation, Property<?> column) {
		Codec<?> codec = columnCodec(relation, column);
		return codec != null && codec.isAcceptsNull();
	}

	private Codec<?> columnCodec(Relation relation, Property<?> column) {
		Map<String, Codec<?>> perColumn = byColumn.get(relation.getName());
		return perColumn == null ? null : perColumn.get(column.getName());
	}
}
