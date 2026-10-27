// ABOUTME: Relation declarations: relations, columns, schemas and anonymous rows, the projection
// ABOUTME: marker, and the answer codec. The literals built over them live in the literals subpackage.

/**
 * How a relation is declared. {@link org.clauseway.pldb.relations.Relation}
 * names a relation and its {@link org.clauseway.pldb.relations.Property} columns;
 * {@link org.clauseway.pldb.relations.RelationN} is its value implementation.
 * {@link org.clauseway.pldb.relations.Schema} is a relation schema as a type, an
 * instance being a row of it, with {@link org.clauseway.pldb.relations.Schema.Anonymous}
 * for a row of no Java type. {@link org.clauseway.pldb.relations.Projected} marks a
 * column projected away at the call site, {@link org.clauseway.pldb.relations.MagicVar}
 * being the marker base. {@link org.clauseway.pldb.relations.Answers} encodes and
 * decodes {@code Answer<Relation>} rows. A relation applied to a row is a literal,
 * built and read in the {@code literals} subpackage.
 */
package org.clauseway.pldb.relations;
