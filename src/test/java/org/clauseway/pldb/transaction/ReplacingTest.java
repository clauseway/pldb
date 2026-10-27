package org.clauseway.pldb.transaction;

// ABOUTME: The refresh doors: replacing lands what a derived literal answers in a stored relation's
// ABOUTME: shape after everything the stored literal answers has left, on the query's own driver; clearing is the retract half alone.

import static org.assertj.core.api.Assertions.assertThat;
import static org.clauseway.logic.unification.terms.LVal.lval;
import static org.clauseway.logic.unification.terms.LVar.lvar;
import static org.clauseway.pldb.relations.Projected.projected;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import org.clauseway.functional.fibers.schedulers.BreadthFirstScheduler;
import org.clauseway.logic.solving.Query;
import org.clauseway.logic.unification.terms.Unifiable;
import org.clauseway.pldb.AnswerSource;
import org.clauseway.pldb.inmemory.SharedDatabase;
import org.clauseway.pldb.relations.literals.External;
import org.clauseway.pldb.relations.literals.Literal;
import org.clauseway.pldb.relations.Schema;
import org.junit.Test;

public class ReplacingTest {

	private static External<Schema.Anonymous> person(AnswerSource db, Unifiable<Integer> id, Unifiable<String> name) {
		return Literal.relation(ReplacingTest.class, "person")
				.arg("id", id).indexed()
				.arg("name", name)
				.from(db);
	}

	/** The person's name with a title: the derived face, in the stored relation's shape. */
	private static Literal<Schema.Anonymous> titled(AnswerSource db, Unifiable<Integer> id, Unifiable<String> name) {
		return Literal.relation(ReplacingTest.class, "titled")
				.arg("id", id)
				.arg("name", name)
				.solving(person(db, id, projected()).and(name.unifies("Dr. Ada")));
	}

	private static List<String> names(AnswerSource db) {
		Unifiable<String> name = lvar();
		return Query.of(person(db, lvar(), name)).solve(name)
				.map(r -> r.get())
				.sorted()
				.collect(Collectors.toList());
	}

	private static SharedDatabase seeded() throws Transaction.Conflict {
		SharedDatabase store = SharedDatabase.empty();
		Transaction.recording(store.open("seed"))
				.asserting(person(null, lval(1), lval("Ada")), person(null, lval(2), lval("Bob")))
				.commit();
		return store;
	}

	@Test
	public void replacingLandsTheDerivedRowsWhereTheStoredOnesWere() throws Exception {
		SharedDatabase store = seeded();
		Transaction writer = Transaction.recording(store.open("w"));
		Unifiable<String> name = lvar();
		writer = writer.replacing(person(writer, lval(1), name), Query.of(titled(writer, lval(1), name)));
		assertThat(names(writer)).containsExactly("Bob", "Dr. Ada");
		writer.commit();
		try (Transaction reader = Transaction.recording(store.open("r"))) {
			assertThat(names(reader)).containsExactly("Bob", "Dr. Ada");
		}
	}

	/** The derivation is the caller's query: whatever driver it names runs the refresh. */
	@Test
	public void theRefreshRunsOnTheQuerysDriver() throws Exception {
		SharedDatabase store = seeded();
		Transaction writer = Transaction.recording(store.open("w"));
		Unifiable<String> name = lvar();
		AtomicBoolean driven = new AtomicBoolean();
		Query derived = Query.of(titled(writer, lval(1), name)).on(fiber -> {
			driven.set(true);
			return new BreadthFirstScheduler<>(fiber);
		});
		writer = writer.replacing(person(writer, lval(1), name), derived);
		assertThat(driven.get()).isTrue();
		assertThat(names(writer)).containsExactly("Bob", "Dr. Ada");
	}

	@Test
	public void clearingRemovesEveryRowTheStoredLiteralAnswers() throws Exception {
		SharedDatabase store = seeded();
		Transaction opened = Transaction.recording(store.open("w"));
		Transaction writer = opened.clearing(person(opened, lval(1), lvar()));
		assertThat(names(writer)).containsExactly("Bob");
	}
}
