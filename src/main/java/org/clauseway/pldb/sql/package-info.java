// ABOUTME: The JDBC tier: fetching and flushing relation rows over a connection by naming
// ABOUTME: convention, codecs at the wire, region push-down, and serialization protocols.

/**
 * Relation sources and writers over JDBC. The convention is that a relation's name is
 * the table and its property names are the columns. {@link org.clauseway.pldb.sql.SqlFetch}
 * executes a probe as a SELECT, pushing the probe's region into the WHERE through
 * {@link org.clauseway.pldb.sql.SqlCompiler} instances registered per constraint family;
 * {@link org.clauseway.pldb.sql.CachingSqlFetch} composes it with
 * {@link org.clauseway.pldb.sql.CachingAnswerSource}, which serves subsumed probes from a
 * pool. {@link org.clauseway.pldb.sql.SqlFlush} lands rows, and
 * {@link org.clauseway.pldb.sql.Codec} with {@link org.clauseway.pldb.sql.Codecs} translate
 * column values across the wire. {@link org.clauseway.pldb.sql.Watermark},
 * {@link org.clauseway.pldb.sql.VersionedWatermark} and
 * {@link org.clauseway.pldb.sql.SerializableSource} implement the transaction package's
 * serialization contracts; all of them are {@link org.clauseway.pldb.sql.JdbcSource}s.
 * The compilers for the engine's own constraint families live in the sql.compiler package.
 */
package org.clauseway.pldb.sql;
