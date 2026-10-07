// ABOUTME: The contracts between a relation lookup and whatever answers it: the sync and
// ABOUTME: async source kinds, the write contract, and a producer that runs a rule as a goal.

/**
 * The contracts a relation lookup is served through. {@link org.clauseway.pldb.AnswerSource}
 * answers a {@code Call<Relation>} probe by enumerating {@code Answer<Relation>} rows inline;
 * {@link org.clauseway.pldb.AnswerProducer} is the asynchronous kind, emitting rows as a
 * {@code Fiber} on the caller's scheduler, and {@link org.clauseway.pldb.AnswerProducer#of}
 * lifts a sync source into it. {@link org.clauseway.pldb.GoalProducer} is a producer whose
 * rows come from running a {@code Goal} through {@code Query} against an injected tabling
 * {@code Table}. {@link org.clauseway.pldb.Writer} is the write contract: asserting and
 * retracting rows, with {@code Literal} statements converted at the boundary. The relations
 * package builds literals over these contracts; the inmemory, sql and transaction packages
 * implement them.
 */
package org.clauseway.pldb;
