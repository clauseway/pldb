// ABOUTME: The in-memory tier: a persistent answer store indexed by the relation's
// ABOUTME: declared columns, and a shared database that opens it as a serialized source.

/**
 * In-memory implementations of the root package's contracts.
 * {@link org.clauseway.pldb.inmemory.AnswerStore} is a persistent value implementing
 * {@code AnswerSource} and {@code Writer}: rows are membership claims, columns flagged
 * {@code indexed()} keep per-value buckets, and every write returns a new store.
 * {@link org.clauseway.pldb.inmemory.SharedDatabase} holds one mutable history of such a
 * store and {@link org.clauseway.pldb.inmemory.SharedDatabase#open} hands out a snapshot
 * as a {@code SimulatedSerialization} source for the transaction package. The sql package's
 * {@code CachingAnswerSource} and the transaction package's {@code WriteBuffer} use
 * {@code AnswerStore} as their row pool.
 */
package org.clauseway.pldb.inmemory;
