package org.clauseway.pldb.transaction;

// ABOUTME: The transaction: a read face, a write face staging facts, and a commit
// ABOUTME: proven through the source — recording the reads, or leaning on the backend.

import java.util.concurrent.ConcurrentHashMap;
import org.clauseway.pldb.AnswerSource;
import org.clauseway.pldb.Writer;

/**
 * One transaction over one serialized source: an {@link AnswerSource}
 * read face, a write face staging facts, and a {@link #commit()} proven
 * through the source's own serialization door. WHERE the proof of the
 * read set lives is the client's choice, bounded by what the source
 * offers: {@link #over} leans on a {@link NativeSerialization} backend
 * that tracks the reads itself — invisible, and gone at commit;
 * {@link #recording} pins every read through a
 * {@link SimulatedSerialization} source and holds the proof as a
 * {@link Footprint} value, which outlives the transaction and travels
 * to a later request as a premise (an over-approximation — retries,
 * never unsoundness). A source offering both proofs opens either way;
 * a source offering neither has no door to call: a transaction exists
 * FOR its write face — reads alone never need one.
 */
public interface Transaction extends AnswerSource, Writer<Transaction>, AutoCloseable {

	final class Conflict extends Exception {
		public Conflict(String message) {
			super(message);
		}

		/** The refusal every commit door shares: the world moved under a read. */
		static Conflict moved(String id) {
			return new Conflict(id + ": a concurrent commit moved a region this transaction read — re-solve");
		}
	}

	/** Reads unrecorded: the backend proves them at its own commit. */
	static Transaction over(NativeSerialization source) {
		return new NativeTransaction(WriteBuffer.over(source), source);
	}

	/** Reads pinned: the transaction proves its footprint, and any premise, at commit. */
	static RecordingTransaction recording(SimulatedSerialization source) {
		return new RecordingTransaction(WriteBuffer.over(source), source, new ConcurrentHashMap<>(), Footprint.empty());
	}

	/**
	 * The write face: the source's own commit door proves the binding and
	 * lands the flush. Success or refusal, the value is spent — close it.
	 */
	void commit() throws Conflict;
}
