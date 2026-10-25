package org.clauseway.pldb.transaction;

// ABOUTME: Recording is the client's choice, not the source's kind: a source offering
// ABOUTME: both proofs opens both ways, and each door uses only its own proof.

import static org.assertj.core.api.Assertions.assertThat;
import static org.clauseway.logic.unification.terms.LVar.lvar;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import lombok.Value;
import org.clauseway.logic.solving.Answer;
import org.clauseway.logic.solving.Call;
import org.clauseway.pldb.inmemory.AnswerStore;
import org.clauseway.pldb.relations.Answers;
import org.clauseway.pldb.relations.literals.Literal;
import org.clauseway.pldb.relations.Schema;
import org.clauseway.pldb.relations.Relation;
import org.junit.Test;

public class RecordingChoiceTest {

	@Value
	private static class Generation implements Pin {
		int generation;
	}

	/** A backend that proves reads itself AND mints pins; records which doors are taken. */
	private static final class Dual implements NativeSerialization, SimulatedSerialization {
		private final List<String> events = new ArrayList<>();

		@Override
		public Pinned<Iterable<Answer<Relation>>> read(Call<Relation> probe) {
			events.add("pinned-read");
			return Pinned.of(Collections.emptyList(), new Generation(0));
		}

		@Override
		public Iterable<Answer<Relation>> answers(Call<Relation> probe) {
			events.add("plain-read");
			return Collections.emptyList();
		}

		@Override
		public boolean commit(Footprint read, List<Answer<Relation>> asserted, List<Answer<Relation>> retracted) {
			events.add("footprint-commit:" + read.pins().size());
			return true;
		}

		@Override
		public boolean commit(List<Answer<Relation>> asserted, List<Answer<Relation>> retracted) {
			events.add("native-commit");
			return true;
		}

		@Override
		public long estimate(Call<Relation> probe) {
			return Long.MAX_VALUE;
		}

		@Override
		public String id() {
			return "dual";
		}

		@Override
		public void close() {
		}
	}

	private static final Relation PERSON = Literal.relation(RecordingChoiceTest.class, "person")
			.schema(Schema.builder()
					.arg("id", lvar()).indexed()
					.arg("name", lvar())
					.build())
			.from(AnswerStore.empty())
			.getRelation();

	private static Call<Relation> anyone() {
		return Call.of(PERSON, Answers.image(lvar(), lvar()));
	}

	@Test
	public void overLeansOnTheBackendsOwnProof() throws Exception {
		Dual dual = new Dual();
		try (Transaction transaction = Transaction.over(dual)) {
			transaction.answers(anyone());
			transaction.commit();
		}
		assertThat(dual.events).containsExactly("plain-read", "native-commit");
	}

	@Test
	public void recordingPinsEveryReadAndProvesTheFootprint() throws Exception {
		Dual dual = new Dual();
		try (RecordingTransaction transaction = Transaction.recording(dual)) {
			transaction.answers(anyone());
			transaction.commit();
		}
		assertThat(dual.events).containsExactly("pinned-read", "footprint-commit:1");
	}
}
