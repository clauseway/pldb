package org.clauseway.pldb.sql;

// ABOUTME: The end-to-end theory battery: each constraint theory solved three ways
// ABOUTME: — pushed SQL, unpushed SQL, in-memory reference — and all must agree.

import org.clauseway.logic.solving.Query;
import static org.assertj.core.api.Assertions.assertThat;
import static org.clauseway.logic.finitedomain.FiniteDomain.dom;
import static org.clauseway.logic.nogoods.Exclusion.exclude;
import static org.clauseway.logic.unification.terms.LVal.lval;
import static org.clauseway.logic.unification.terms.LVar.lvar;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.BiFunction;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.clauseway.functional.tuples.Tuple;
import org.clauseway.functional.tuples.Tuple2;
import org.clauseway.logic.constraints.Posting;
import org.clauseway.logic.finitedomain.Longs;
import org.clauseway.logic.goals.Goal;
import org.clauseway.logic.solving.Call;
import org.clauseway.logic.unification.terms.Any;
import org.clauseway.logic.unification.terms.Term;
import org.clauseway.logic.unification.terms.Unifiable;
import org.clauseway.pldb.AnswerSource;
import org.clauseway.pldb.inmemory.AnswerStore;
import org.clauseway.logic.solving.Answer;
import org.clauseway.pldb.relations.Answers;
import org.clauseway.pldb.relations.Literal;
import org.clauseway.pldb.relations.Property;
import org.clauseway.pldb.relations.Relation;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * The oracle is the in-memory reference: for every theory shape the compiler
 * claims to push, the pushed SQL source, the unpushed SQL source, and the
 * reference database must produce identical answer lists over the same
 * program — and every battery entry asserts non-vacuity, so a theory that
 * silently stopped matching rows cannot pass.
 */
public class SqlTheoryBatteryTest {

	private static Literal person(AnswerSource db, Unifiable<Long> id, Unifiable<String> name) {
		return Literal.relation(SqlTheoryBatteryTest.class, "person")
				.arg("id", id).indexed()
				.arg("name", name)
				.from(db);
	}

	private static Relation personRel() {
		return person(null, lvar(), lvar()).getRel();
	}

	private static Literal edge(AnswerSource db, Unifiable<Long> lo, Unifiable<Long> hi) {
		return Literal.relation(SqlTheoryBatteryTest.class, "edge")
				.arg("lo", lo).indexed()
				.arg("hi", hi).indexed()
				.from(db);
	}

	private static Relation edgeRel() {
		return edge(null, lvar(), lvar()).getRel();
	}

	private static final Property<Long> id = Property.of("id");
	private static final Property<String> name = Property.of("name");

	private static final Property<Long> lo = Property.of("lo");
	private static final Property<Long> hi = Property.of("hi");

	private static final AnswerStore reference = AnswerStore.empty()
			.asserting(Arrays.asList(
					person(null, lval(1L), lval("Ada")),
					person(null, lval(2L), lval("Alan")),
					person(null, lval(3L), lval("Kurt")),
					person(null, lval(4L), lval("Barbara")),
					person(null, lval(5L), lval("Edsger")),
					edge(null, lval(1L), lval(2L)),
					edge(null, lval(2L), lval(1L)),
					edge(null, lval(3L), lval(3L)),
					edge(null, lval(1L), lval(5L))));

	private Connection connection;

	/** The whole reference relation, enumerated through the answers face. */
	private static Stream<Answer<Relation>> allFacts(Relation relation) {
		List<Term<?>> members = new ArrayList<>();
		for (int i = 0; i < relation.getArgs().length; i++) {
			members.add(Any.of(i));
		}
		return StreamSupport.stream(reference.answers(
								Call.of(relation, Answers.image(members)))
						.spliterator(), false)
				.map(answer -> Answers.answer(relation, Answers.values(answer.getReified())));
	}

	@Before
	public void loadH2() throws SQLException {
		connection = DriverManager.getConnection("jdbc:h2:mem:");
		try (Statement ddl = connection.createStatement()) {
			ddl.execute("CREATE TABLE person(id BIGINT, name VARCHAR(64))");
			ddl.execute("INSERT INTO person VALUES " +
					allFacts(personRel())
							.map(f -> "(" + Answers.get(f, id).get() + ", '" + Answers.get(f, name).get() + "') ")
							.collect(Collectors.joining(",")));
			ddl.execute("CREATE TABLE edge(lo BIGINT, hi BIGINT)");
			ddl.execute("INSERT INTO edge VALUES " +
					allFacts(edgeRel())
							.map(f -> "(" + Answers.get(f, lo).get() + ", " + Answers.get(f, hi).get() + ") ")
							.collect(Collectors.joining(",")));
		}
	}

	@After
	public void closeH2() throws SQLException {
		connection.close();
	}

	@Test
	public void intervalDomain() {
		agree(2, (AnswerSource source, Unifiable<String> out) -> {
			Unifiable<Long> x = lvar();
			return dom(x, Longs.interval(2, 3)).and(person(source, x, out));
		});
	}

	@Test
	public void singletonDomainCollapsesToABoundProbe() {
		agree(1, (AnswerSource source, Unifiable<String> out) -> {
			Unifiable<Long> x = lvar();
			return dom(x, Longs.range(2, 3)).and(person(source, x, out));
		});
	}

	@Test
	public void holeyDomainThroughTheSolveIsAUnion() {
		// separate punches the hole DURING propagation: the probe's region
		// carries a real Union, pushed as its members disjoined — [1,5] minus
		// {3} keeps four
		agree(4, (AnswerSource source, Unifiable<String> out) -> {
			Unifiable<Long> x = lvar();
			return dom(x, Longs.interval(1, 5))
					.and(Longs.separate(x, lval(3L)))
					.and(person(source, x, out));
		});
	}

	@Test
	public void strictOrderAgainstAValue() {
		agree(2, (AnswerSource source, Unifiable<String> out) -> {
			Unifiable<Long> x = lvar();
			return Longs.lss(x, lval(3L)).and(person(source, x, out));
		});
	}

	@Test
	public void flippedStrictOrderAgainstAValue() {
		agree(2, (AnswerSource source, Unifiable<String> out) -> {
			Unifiable<Long> x = lvar();
			return Longs.gtr(x, lval(3L)).and(person(source, x, out));
		});
	}

	@Test
	public void looseOrderAgainstAValue() {
		agree(3, (AnswerSource source, Unifiable<String> out) -> {
			Unifiable<Long> x = lvar();
			return Longs.leq(x, lval(3L)).and(person(source, x, out));
		});
	}

	@Test
	public void disequalityAgainstAValue() {
		agree(4, (AnswerSource source, Unifiable<String> out) -> {
			Unifiable<Long> x = lvar();
			return Longs.separate(x, lval(3L)).and(person(source, x, out));
		});
	}

	@Test
	public void strictOrderAcrossTwoColumns() {
		agree(2, (AnswerSource source, Unifiable<Long> out) -> {
			Unifiable<Long> b = lvar();
			return Longs.lss(out, b).and(edge(source, out, b));
		});
	}

	@Test
	public void looseOrderAcrossTwoColumns() {
		agree(3, (AnswerSource source, Unifiable<Long> out) -> {
			Unifiable<Long> b = lvar();
			return Longs.leq(out, b).and(edge(source, out, b));
		});
	}

	@Test
	public void disequalityAcrossTwoColumns() {
		agree(3, (AnswerSource source, Unifiable<Long> out) -> {
			Unifiable<Long> b = lvar();
			return Longs.separate(out, b).and(edge(source, out, b));
		});
	}

	@Test
	public void oneLiteralExclusion() {
		agree(4, (AnswerSource source, Unifiable<String> out) -> {
			Unifiable<Long> x = lvar();
			return exclude(x.unifies(3L)).and(person(source, x, out));
		});
	}

	@Test
	public void multiLiteralExclusionExcludesTheRowNotTheColumns() {
		// ¬(id=2 ∧ name='Alan') kills exactly the one row where BOTH hold
		agree(4, (AnswerSource source, Unifiable<String> out) -> {
			Unifiable<Long> x = lvar();
			return exclude(Posting.all(x.unifies(2L), out.unifies("Alan")))
					.and(person(source, x, out));
		});
	}

	@Test
	public void aConjoinedTheoryPushesAllItsAtoms() {
		agree(1, (AnswerSource source, Unifiable<String> out) -> {
			Unifiable<Long> x = lvar();
			return dom(x, Longs.interval(2, 5))
					.and(Longs.lss(x, lval(4L)))
					.and(Longs.separate(x, lval(2L)))
					.and(person(source, x, out));
		});
	}

	private <T> void agree(int expected, BiFunction<AnswerSource, Unifiable<T>, Goal> program) {
		Tuple2<AnswerSource, AnswerSource> sources = Tuple.of(
				CachingSqlFetch.pinned("battery-push", connection),
				CachingAnswerSource.over(SqlFetch.pinned("battery-plain", connection)));
		List<String> pushed = answers(sources._1, program);
		List<String> unpushed = answers(sources._2, program);
		List<String> inMemory = answers(reference, program);
		assertThat(pushed).isEqualTo(inMemory);
		assertThat(unpushed).isEqualTo(inMemory);
		assertThat(pushed).hasSize(expected);
	}

	private static <T> List<String> answers(AnswerSource source, BiFunction<AnswerSource, Unifiable<T>, Goal> program) {
		Unifiable<T> out = lvar();
		return Query.of(program.apply(source, out)).solve(out)
				.map(Object::toString)
				.sorted()
				.collect(Collectors.toList());
	}
}
