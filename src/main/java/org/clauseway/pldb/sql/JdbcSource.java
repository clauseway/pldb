package org.clauseway.pldb.sql;

import java.sql.Connection;
import org.clauseway.pldb.AnswerSource;

/**
 * An {@link AnswerSource} backed by one JDBC {@link Connection}:
 * {@link #getConnection()} exposes it, {@link #codecs()} the column codecs
 * the source encodes and decodes through, and {@link #close()} releases
 * it. {@link SqlFetch}, {@link CachingSqlFetch}, {@link Watermark},
 * {@link VersionedWatermark} and {@link SerializableSource} implement it.
 */
public interface JdbcSource extends AnswerSource, AutoCloseable {
	Connection getConnection();

	/** The source's value↔wire table; builtins only unless overridden. */
	default Codecs codecs() {
		return Codecs.builtin();
	}
}
