package org.clauseway.pldb.transaction;

// ABOUTME: The transaction whose reads the backend proves: it records nothing,
// ABOUTME: buffers writes, and hands the flush to the native commit door.

import java.util.List;
import org.clauseway.logic.solving.Answer;
import org.clauseway.logic.solving.Call;
import org.clauseway.pldb.relations.Relation;

/**
 * The backend tracks every read it serves, so this transaction records
 * nothing — it buffers writes and hands the flush to the source's
 * {@link NativeSerialization} door.
 */
final class NativeTransaction implements Transaction {

	private final WriteBuffer writeBuffer;
	private final NativeSerialization serialization;

	NativeTransaction(WriteBuffer writeBuffer, NativeSerialization serialization) {
		this.writeBuffer = writeBuffer;
		this.serialization = serialization;
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

	@Override
	public Transaction asserting(List<Answer<Relation>> rows) {
		return new NativeTransaction(writeBuffer.asserting(rows), serialization);
	}

	@Override
	public Transaction retracting(List<Answer<Relation>> rows) {
		return new NativeTransaction(writeBuffer.retracting(rows), serialization);
	}

	@Override
	public void commit() throws Conflict {
		if (!serialization.commit(writeBuffer.stagedAssertions(), writeBuffer.stagedRetractions())) {
			throw Conflict.moved(id());
		}
	}

	/** Ends the snapshot (the source's close rolls its read transaction back). */
	@Override
	public void close() throws Exception {
		serialization.close();
	}
}
