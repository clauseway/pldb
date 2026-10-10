// ABOUTME: Transactions over a serialized source: staged writes, pinned reads, footprints,
// ABOUTME: and the two serialization contracts a source commits through.

/**
 * One transaction over one serialized source.
 * {@link org.clauseway.pldb.transaction.Transaction} is the entry point: an
 * {@code AnswerSource} read side, a {@code Writer} staging assertions and retractions in
 * a {@link org.clauseway.pldb.transaction.WriteBuffer}, and
 * {@link org.clauseway.pldb.transaction.Transaction#commit()} proven through the source.
 * Where the proof of the reads lives is the client's choice, bounded by the source's
 * contracts: {@link org.clauseway.pldb.transaction.Transaction#over} over a
 * {@link org.clauseway.pldb.transaction.NativeSerialization} source records nothing because
 * the backend validates its own reads;
 * {@link org.clauseway.pldb.transaction.Transaction#recording} over a
 * {@link org.clauseway.pldb.transaction.SimulatedSerialization} source yields a
 * {@link org.clauseway.pldb.transaction.RecordingTransaction} that records every read as a
 * {@link org.clauseway.pldb.transaction.Pinned} value and proves its
 * {@link org.clauseway.pldb.transaction.Footprint} of {@link org.clauseway.pldb.transaction.Pin}s
 * unmoved at commit — a proof that outlives the transaction and travels as a premise.
 * The inmemory package's {@code SharedDatabase} and the sql package's {@code Watermark},
 * {@code VersionedWatermark} and {@code SerializableSource} implement these contracts.
 */
package org.clauseway.pldb.transaction;
