package org.clauseway.pldb.transaction;

// ABOUTME: What the two transaction kinds share: the write buffer answering the
// ABOUTME: read face, and the commit verdict mapping (refused = Conflict).

import java.util.function.BooleanSupplier;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.clauseway.logic.solving.Call;
import org.clauseway.logic.solving.Answer;
import org.clauseway.pldb.relations.Relation;

/**
 * The base of {@link Simulated} and {@link Native}: reads answer through
 * the {@link WriteBuffer} (the source overlaid with this transaction's
 * staged delta), and {@link #through} maps a serialization door's verdict
 * for both kinds' {@link #commit()}.
 */
@RequiredArgsConstructor(access = AccessLevel.MODULE)
abstract class AbstractTransaction implements Transaction {
	final WriteBuffer writeBuffer;

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
