// ABOUTME: Transactions over a serialized source: staged writes, pinned reads, footprints,
// ABOUTME: and the two serialization contracts a source commits through.

/**
 * One transaction over one serialized source.
 * {@link org.clauseway.pldb.transaction.Transaction} is the entry point: an
 * {@code AnswerSource} read side, a {@code Writer} staging assertions and retractions in
 * a {@link org.clauseway.pldb.transaction.WriteBuffer}, and
 * {@link org.clauseway.pldb.transaction.Transaction#commit()} proven through the source.
 * {@link org.clauseway.pldb.transaction.AbstractTransaction#over} chooses the subtype by the
 * source's contract: {@link org.clauseway.pldb.transaction.Simulated} over a
 * {@link org.clauseway.pldb.transaction.SimulatedSerialization} source records every read as a
 * {@link org.clauseway.pldb.transaction.Pinned} value and proves its
 * {@link org.clauseway.pldb.transaction.Footprint} of {@link org.clauseway.pldb.transaction.Pin}s
 * unmoved at commit; {@link org.clauseway.pldb.transaction.Native} over a
 * {@link org.clauseway.pldb.transaction.NativeSerialization} source records nothing because
 * the backend validates its own reads. The inmemory package's {@code SharedDatabase} and the
 * sql package's {@code Watermark}, {@code VersionedWatermark} and {@code SerializableSource}
 * implement these contracts.
 */
package org.clauseway.pldb.transaction;
