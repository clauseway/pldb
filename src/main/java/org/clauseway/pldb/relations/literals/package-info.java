// ABOUTME: Literals: a relation applied to a row, in three kinds — over a source, over a producer, defined by a
// ABOUTME: rule — the first two External and so writable; the builder, the projection it applies, the question.

/**
 * A {@link org.clauseway.pldb.relations.literals.Literal} is a relation applied to a
 * row, built through {@code Literal.relation(ns, name).args(row)} and a terminal
 * that picks its kind: {@code from} a source, {@code produced} by a producer, or
 * {@code solving} a rule. The first two are
 * {@link org.clauseway.pldb.relations.literals.External}, the write face a
 * transaction asserts and retracts; a rule literal is not. A {@code projected()}
 * column drops out of the head into a generated projection rule, applied by the
 * builder. {@link org.clauseway.pldb.relations.literals.Question#select} runs a
 * goal through {@code Query} and collects its answers as rows shaped by template
 * literals.
 */
package org.clauseway.pldb.relations.literals;
