// ABOUTME: SQL push-down compilers for the engine's own constraint families, and the
// ABOUTME: predicate vocabulary they compile into.

/**
 * Compilers from constraint atoms to SQL WHERE fragments.
 * {@link org.clauseway.pldb.sql.compiler.SqlPredicate} is the compiled form: a condition
 * fragment with its bound parameters, built from the static constructors such as
 * {@link org.clauseway.pldb.sql.compiler.SqlPredicate#in} and
 * {@link org.clauseway.pldb.sql.compiler.SqlPredicate#between}.
 * {@link org.clauseway.pldb.sql.compiler.FiniteDomainSqlCompiler} compiles finite-domain
 * impositions and order propagators, and
 * {@link org.clauseway.pldb.sql.compiler.NogoodSqlCompiler} compiles nogoods by negating
 * their literals through the other registered compilers. Both implement the sql package's
 * {@code SqlCompiler}, which {@code SqlFetch} registers per constraint family.
 */
package org.clauseway.pldb.sql.compiler;
