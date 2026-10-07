package org.clauseway.pldb.sql;

// ABOUTME: One column's two-way translation: a value flattens to a
// ABOUTME: JDBC-representable on write and re-forms from it on read.

import java.util.function.Function;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.clauseway.logic.unification.terms.Unifiable;
import org.clauseway.pldb.relations.MagicVar;

/**
 * How a column's values cross the JDBC boundary: the encoder flattens
 * a value to its wire form on write and into bound probe parameters;
 * the decoder re-forms it from the cells the driver returns. A codec
 * binds to a COLUMN, not to a type — the same Java type may encode
 * differently in different columns — and the binding is stated through
 * {@link #arg()}: pass the marker through the relation's defining method
 * into {@code withCodec}, and the method's signature makes a mis-slotted
 * codec a compile error.
 */
@Getter
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public final class Codec<T> {

	private final Class<T> valueType;
	private final Class<?> jdbcType;
	private final Function<T, Object> encoder;
	private final Function<Object, T> decoder;

	public static <T, J> Codec<T> of(Class<T> valueType, Class<J> jdbcType,
			Function<T, J> encoder, Function<J, T> decoder) {
		return new Codec<>(valueType, jdbcType,
				encoder::apply,
				cell -> decoder.apply(jdbcType.cast(cell)));
	}

	/**
	 * The registration marker: a fresh variable carrying this codec into
	 * one arg slot of a template literal —
	 * {@code source.withCodec(loan(null, lvar(), DATES.arg()))} binds the
	 * codec to that column.
	 */
	public Unifiable<T> arg() {
		return new Arg<>(this);
	}

	Object encode(Object value) {
		return encoder.apply(valueType.cast(value));
	}

	Object decode(Object cell) {
		return decoder.apply(cell);
	}

	/** A fresh variable everywhere except {@code withCodec}, which reads the wrapper. */
	@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
	static final class Arg<T> extends MagicVar<T> {

		@Getter(AccessLevel.PACKAGE)
		private final Codec<T> codec;
	}
}
