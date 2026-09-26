package org.clauseway.pldb.transaction;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentMap;
import org.clauseway.logic.solving.Call;
import org.clauseway.logic.solving.Answer;
import org.clauseway.pldb.relations.Relation;

/**
 * SIMULATED serialization: this transaction's ledger is the read cache
 * AND the recorder in one — a region's FIRST touch reads through the
 * source (pin and rows minted from one world) and every repeat serves
 * the same {@link Pinned} back, so the transaction is its own snapshot
 * at region grain: read stability by construction, no source consulted
 * twice for one region. Commit proves the {@link #footprint()} of its
 * own reads UNIONED with the {@link #requiring(Footprint) premise} —
 * regions a client read in an EARLIER request, whose decision this
 * write carries out: the commit stands on both, and either world
 * having moved refuses it. The union's own refusal guards overlapping
 * regions read at different worlds.
 */
public class Simulated extends AbstractTransaction {

	private final SimulatedSerialization serialization;
	private final ConcurrentMap<Call<Relation>, Pinned<Iterable<Answer<Relation>>>> reads;
	private final Footprint premise;

	Simulated(WriteBuffer writeBuffer, SimulatedSerialization serialization,
			ConcurrentMap<Call<Relation>, Pinned<Iterable<Answer<Relation>>>> reads, Footprint premise) {
		super(writeBuffer);
		this.serialization = serialization;
		this.reads = reads;
		this.premise = premise;
	}

	@Override
	public Iterable<Answer<Relation>> answers(Call<Relation> probe) {
		Pinned<Iterable<Answer<Relation>>> read = reads.computeIfAbsent(probe, serialization::read);
		return writeBuffer.overlay(probe, read.getValue());
	}

	@Override
	public Transaction asserting(List<Answer<Relation>> rows) {
		return new Simulated(writeBuffer.asserting(rows), serialization, reads, premise);
	}

	@Override
	public Transaction retracting(List<Answer<Relation>> rows) {
		return new Simulated(writeBuffer.retracting(rows), serialization, reads, premise);
	}

	/** The ledger folded: every region this transaction read, at its pin. */
	public Footprint footprint() throws Transaction.Conflict {
		Footprint folded = Footprint.empty();
		for (Map.Entry<Call<Relation>, Pinned<Iterable<Answer<Relation>>>> read : reads.entrySet()) {
			folded = folded.union(Footprint.of(read.getKey(), read.getValue().getPin()));
		}
		return folded;
	}

	/**
	 * The client's premise: regions read ELSEWHERE (an earlier request,
	 * another transaction) that this commit must also prove unmoved —
	 * the decision behind the write stood on them, whether or not this
	 * transaction reads them itself. Premises accumulate by union.
	 */
	public Simulated requiring(Footprint required) throws Transaction.Conflict {
		return new Simulated(writeBuffer, serialization, reads, premise.union(required));
	}

	@Override
	public void commit() throws Conflict {
		Footprint certified = footprint().union(premise);
		through(() -> serialization.commit(certified,
				writeBuffer.stagedAssertions(),
				writeBuffer.stagedRetractions()));
	}

	/** Ends the snapshot (the source's close rolls its read transaction back). */
	@Override
	public void close() throws Exception {
		serialization.close();
	}
}
