// ABOUTME: Relation declarations and their use as goals: relations, columns, literals
// ABOUTME: built over a source, the answer codec, and selecting rows out of a question.

/**
 * How a relation is declared and applied. {@link org.clauseway.pldb.relations.Relation}
 * names a relation and its {@link org.clauseway.pldb.relations.Property} columns;
 * {@link org.clauseway.pldb.relations.RelationN} is its value implementation.
 * {@link org.clauseway.pldb.relations.Literal} is a relation applied to arguments: built
 * through {@link org.clauseway.pldb.relations.Literal#relation}, read as a {@code Goal} in
 * a conjunction, or posted as a constraint through the constraints package.
 * {@link org.clauseway.pldb.relations.Projected} marks a column projected away at the call
 * site, {@link org.clauseway.pldb.relations.MagicVar} being the marker base.
 * {@link org.clauseway.pldb.relations.Answers} encodes and decodes {@code Answer<Relation>}
 * rows, and {@link org.clauseway.pldb.relations.Question#select} runs a goal through
 * {@code Query} and collects its answers as rows shaped by template literals.
 */
package org.clauseway.pldb.relations;
