package org.clauseway.pldb.transaction;

// ABOUTME: The transaction ledger: a region's FIRST touch reads through the
// ABOUTME: source, repeats serve from the ledger, commit carries that one pin.

import static org.assertj.core.api.Assertions.assertThat;
import static org.clauseway.logic.unification.terms.LVar.lvar;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import lombok.Value;
import org.clauseway.logic.solving.Call;
import org.clauseway.logic.unification.terms.Unifiable;
import org.clauseway.pldb.AnswerSource;
import org.clauseway.logic.solving.Answer;
import org.clauseway.pldb.relations.Literal;
import org.clauseway.pldb.relations.Relation;
import org.junit.Test;

public class PinAtFirstTouchTest {

	@Value
	private static class Generation implements Pin {
		int generation;
	}

	/** Mints a FRESH generation pin per read; records its door calls. */
	private static final class Recording implements SimulatedSerialization {
		private final List<String> events = new ArrayList<>();
		private final List<Pin> committed = new ArrayList<>();
		private int generation;

		@Override
		public Pinned<Iterable<Answer<Relation>>> read(Call<Relation> probe) {
			events.add("read:" + probe.getRelation().getName());
			return Pinned.of(Collections.emptyList(), new Generation(generation++));
		}

		@Override
		public boolean commit(Footprint read, List<Answer<Relation>> asserted, List<Answer<Relation>> retracted) {
			events.add("commit:" + read.pins().size());
			committed.addAll(read.pins().values());
			return true;
		}

		@Override
		public long estimate(Call<Relation> probe) {
			return Long.MAX_VALUE;
		}

		@Override
		public String id() {
			return "recording";
		}

		@Override
		public void close() {
		}
	}

	private static Literal person(AnswerSource db, Unifiable<Integer> id, Unifiable<String> name) {
		return Literal.relation(PinAtFirstTouchTest.class, "person")
				.arg("id", id).indexed()
				.arg("name", name)
				.from(db);
	}

	@Test
	public void aRegionReadsOnceAndItsPinIsTheFootprint() throws Exception {
		Recording recording = new Recording();
		try (Transaction transaction = AbstractTransaction.over(recording)) {
			solve(transaction);
			solve(transaction);
			transaction.commit();
		}
		// two touches, ONE read: the ledger serves the repeat the same
		// Pinned back — the transaction is its own snapshot at region grain
		assertThat(recording.events).containsExactly("read:person", "commit:1");
		assertThat(recording.committed).containsExactly(new Generation(0));
	}

	private static void solve(Transaction transaction) {
		Unifiable<String> name = lvar();
		assertThat(person(transaction, lvar(), name).solve(name)
				.collect(Collectors.toList())).isEmpty();
	}
}
