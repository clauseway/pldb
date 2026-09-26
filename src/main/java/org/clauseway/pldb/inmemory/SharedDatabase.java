package org.clauseway.pldb.inmemory;

// ABOUTME: The shared in-memory store: one mutable cell of persistent AnswerStore
// ABOUTME: values, opened as snapshots with simulated serialization per relation.

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.Value;
import org.clauseway.logic.solving.Call;
import org.clauseway.pldb.relations.Answer;
import org.clauseway.pldb.relations.Relation;
import org.clauseway.pldb.transaction.Footprint;
import org.clauseway.pldb.transaction.Pin;
import org.clauseway.pldb.transaction.Pinned;
import org.clauseway.pldb.transaction.SimulatedSerialization;

/**
 * The one history of an in-memory world: a mutable cell holding a
 * persistent {@link AnswerStore} value plus per-relation generations.
 * {@link #open} takes a snapshot — free, the value IS the snapshot —
 * and hands it out as a {@link SimulatedSerialization} source: reads
 * come from the captured value forever (stable by construction), the
 * pin is the probed relation's generation, and commit is the memory tier's
 * whole protocol in one synchronized block — prove the footprint's
 * relations unmoved, grow the current value by the flush, bump the
 * moved generations. The degenerate ideal of the simulated ladder:
 * exact marks, a free snapshot, and the monitor as the commit lock.
 */
public final class SharedDatabase {

	@Value
	private static class Versioned {
		AnswerStore value;
		Map<Relation, Long> marks;
	}

	private Versioned current;

	private SharedDatabase(AnswerStore initial) {
		this.current = new Versioned(initial, new HashMap<>());
	}

	public static SharedDatabase empty() {
		return new SharedDatabase(AnswerStore.empty());
	}

	public SimulatedSerialization open(String id) {
		return new Snapshot(id, read());
	}

	private synchronized Versioned read() {
		return current;
	}

	/**
	 * The commit protocol, whole: the monitor is the commit lock, the
	 * generation compare is the proof, the persistent grow is the flush.
	 */
	private synchronized boolean commit(Footprint read, List<Answer> asserted, List<Answer> retracted) {
		if (!covers(read)) {
			return false;
		}
		AnswerStore next = current.getValue()
				.asserting(asserted)
				.retracting(retracted);
		Map<Relation, Long> marks = new HashMap<>(current.getMarks());
		for (Answer row : asserted) {
			marks.merge(row.getRelation(), 1L, Long::sum);
		}
		for (Answer row : retracted) {
			marks.merge(row.getRelation(), 1L, Long::sum);
		}
		current = new Versioned(next, marks);
		return true;
	}

	/** Exact per-relation marks: absence here is knowledge either way. */
	private boolean covers(Footprint read) {
		for (Pin pinned : read.pins().values()) {
			MarkPin pin = (MarkPin) pinned;
			if (!Objects.equals(current.getMarks().get(pin.getRelation()), pin.getMark())) {
				return false;
			}
		}
		return true;
	}

	/** A snapshot of the cell: reads from the captured value, commits to the cell. */
	private final class Snapshot implements SimulatedSerialization {

		private final String id;
		private final Versioned captured;

		private Snapshot(String id, Versioned captured) {
			this.id = id;
			this.captured = captured;
		}

		@Override
		public Pinned<Iterable<Answer>> read(Call<Relation> probe) {
			Relation relation = probe.getRelation();
			return Pinned.of(captured.getValue().answers(probe),
					new MarkPin(relation, captured.getMarks().get(relation)));
		}

		@Override
		public boolean commit(Footprint read, List<Answer> asserted, List<Answer> retracted) {
			return SharedDatabase.this.commit(read, asserted, retracted);
		}

		@Override
		public Iterable<Answer> answers(Call<Relation> probe) {
			return captured.getValue().answers(probe);
		}

		@Override
		public long estimate(Call<Relation> probe) {
			return captured.getValue().estimate(probe);
		}

		@Override
		public String id() {
			return id;
		}

		@Override
		public void close() {
			// a snapshot holds no resources: abandonment is garbage collection
		}
	}

	/**
	 * The probed RELATION's generation, WITHOUT the database value: pin
	 * equality is world identity at relation grain, never a content
	 * comparison — two pins of one unmoved relation are equal whatever
	 * else committed between them, and any commit touching the relation
	 * divides them. A null mark is the never-written relation: absence
	 * is knowledge. The relation rides WHOLE — namespace-bearing
	 * identity, so same-named strangers never share a world — and any
	 * wire crossing marshals it through the serialization that minted
	 * the pin, which is where class names stop.
	 */
	@Value
	private static class MarkPin implements Pin {
		Relation relation;
		Long mark;
	}
}
