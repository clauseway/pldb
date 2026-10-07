package org.clauseway.pldb.transaction;

// ABOUTME: The transaction: a read face, a write face staging facts, and a commit
// ABOUTME: proven by the source's serialization — one subtype per serialization kind.

import org.clauseway.pldb.AnswerSource;
import org.clauseway.pldb.Writer;

/**
 * One transaction over one serialized source: an {@link AnswerSource}
 * read face, a write face staging facts, and a {@link #commit()} proven
 * through the source's own serialization door. The serialization kind decides
 * the SUBTYPE at {@link AbstractTransaction#over}: an {@link Simulated} transaction records
 * every probe and certifies the whole log at commit (an
 * over-approximation — retries, never unsoundness); a {@link Native}
 * transaction records nothing, because its backend tracks the reads
 * itself. A source with neither capability has no {@code over} to call:
 * a transaction exists FOR its write face — reads alone never need one.
 */
public interface Transaction extends AnswerSource, Writer<Transaction>, AutoCloseable {

	final class Conflict extends Exception {
		public Conflict(String message) {
			super(message);
		}
	}

	/**
	 * The write face: the source's own commit door proves the binding and
	 * lands the flush. Success or refusal, the value is spent — close it.
	 */
	void commit() throws Conflict;
}
