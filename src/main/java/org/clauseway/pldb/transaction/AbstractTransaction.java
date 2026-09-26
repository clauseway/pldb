package org.clauseway.pldb.transaction;

// ABOUTME: The transaction: a read face, a write face staging facts, and a commit
// ABOUTME: proven by the source's serialization — one subtype per serialization kind.

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.clauseway.logic.solving.Call;
import org.clauseway.pldb.AnswerSource;
import org.clauseway.logic.solving.Answer;
import org.clauseway.pldb.relations.Relation;

/**
 * One transaction over one serialized source: an {@link AnswerSource}
 * read face, a write face staging facts, and a {@link #commit()} proven
 * through the source's own serialization door. The serialization kind decides
 * the SUBTYPE at {@link #over}: an {@link Simulated} transaction records
 * every probe and certifies the whole log at commit (an
 * over-approximation — retries, never unsoundness); a {@link Native}
 * transaction records nothing, because its backend tracks the reads
 * itself. A source with neither capability has no {@code over} to call:
 * a transaction exists FOR its write face — reads alone never need one.
 */
@RequiredArgsConstructor(access = AccessLevel.MODULE)
public abstract class AbstractTransaction implements Transaction {
	final WriteBuffer writeBuffer;

	public static Simulated over(SimulatedSerialization source) {
		return new Simulated(WriteBuffer.over(source), source, new ConcurrentHashMap<>(), Footprint.empty());
	}

	public static AbstractTransaction over(NativeSerialization source) {
		return new Native(WriteBuffer.over(source), source);
	}

	/** The shared verdict mapping: refused = Conflict, anything thrown surfaces. */
	void through(BooleanSupplier door) throws Transaction.Conflict {
		if (!door.getAsBoolean()) {
			throw new Transaction.Conflict(id()
					+ ": a concurrent commit moved a region this transaction read — re-solve");
		}
	}

	@Override
	public Iterable<Answer<Relation>> answers(Call<Relation> probe) {
		return writeBuffer.answers(probe);
	}

	@Override
	public long estimate(Call<Relation> probe) {
		return writeBuffer.estimate(probe);
	}

	@Override
	public String id() {
		return writeBuffer.id();
	}
}
