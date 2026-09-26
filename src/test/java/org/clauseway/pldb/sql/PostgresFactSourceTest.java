package org.clauseway.pldb.sql;

// ABOUTME: The north star's Phase 2 proof against real PostgreSQL (testcontainers):
// ABOUTME: a nonrecursive and a recursive relation answer identically over memory and PG.

import org.clauseway.pldb.relations.Answers;
import static org.assertj.core.api.Assertions.assertThat;
import static org.clauseway.logic.goals.Goal.defer;
import static org.clauseway.logic.unification.terms.LVal.lval;
import static org.clauseway.logic.unification.terms.LVar.lvar;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.clauseway.functional.tuples.Tuple;
import org.clauseway.logic.goals.Goal;
import org.clauseway.logic.unification.terms.Unifiable;
import org.clauseway.pldb.AnswerSource;
import org.clauseway.pldb.inmemory.AnswerStore;
import org.clauseway.logic.solving.Answer;
import org.clauseway.pldb.relations.Literal;
import org.clauseway.pldb.relations.Property;
import org.clauseway.pldb.relations.Relation;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Assume;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Phase 2's falsifiable result (docs/design/domain-layer.md §12): one
 * nonrecursive and one recursive relation produce the same answer set
 * over in-memory and PostgreSQL facts — the backing swaps under the
 * seam and nothing above it can tell. Skips cleanly when Docker is not
 * available.
 */
public class PostgresFactSourceTest {

	private static Literal person(AnswerSource db, Unifiable<Integer> id, Unifiable<String> name) {
		return Literal.relation(PostgresFactSourceTest.class, "person")
				.arg("id", id).indexed()
				.arg("name", name)
				.from(db);
	}

	private static Relation personRel() {
		return person(null, lvar(), lvar()).getRel();
	}

	private static Literal edge(AnswerSource db, Unifiable<Integer> src, Unifiable<Integer> dst) {
		return Literal.relation(PostgresFactSourceTest.class, "edge")
				.arg("src", src).indexed()
				.arg("dst", dst).indexed()
				.from(db);
	}

	private static Relation edgeRel() {
		return edge(null, lvar(), lvar()).getRel();
	}

	private static final Property<Integer> id = Property.of("id");
	private static final Property<String> name = Property.of("name");
	private static final Property<Integer> src = Property.of("src");
	private static final Property<Integer> dst = Property.of("dst");

	private static final List<Literal> facts = Arrays.asList(
			person(null, lval(1), lval("Ada")),
			person(null, lval(2), lval("Alan")),
			person(null, lval(3), lval("Kurt")),
			edge(null, lval(1), lval(2)),
			edge(null, lval(1), lval(3)),
			edge(null, lval(2), lval(4)),
			edge(null, lval(3), lval(4)));

	private static final AnswerStore reference = AnswerStore.empty()
			.asserting(facts);

	private static PostgreSQLContainer<?> postgres;

	private Connection connection;
	private CachingSqlFetch source;

	@BeforeClass
	public static void startPostgres() {
		Assume.assumeTrue("Docker unavailable — the Phase 2 proof needs it",
				DockerClientFactory.instance().isDockerAvailable());
		postgres = new PostgreSQLContainer<>("postgres:16-alpine");
		postgres.start();
	}

	@AfterClass
	public static void stopPostgres() {
		if (postgres != null) {
			postgres.stop();
		}
	}

	@Before
	public void loadPostgres() throws SQLException {
		connection = DriverManager.getConnection(
				postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
		push(connection, facts);
		source = CachingSqlFetch.pinned("pg", connection);
	}

	/**
	 * The reference facts pushed into the backend by the naming convention
	 * the adapter reads by: the relation's name is the table, its property
	 * names the columns, column types inferred from the values. One fact
	 * list feeds both worlds — the proof compares backings, not fixtures.
	 */
	private static void push(Connection connection, List<Literal> facts) throws SQLException {
		Map<Relation, List<Answer<Relation>>> byRelation = facts.stream()
				.map(Literal::fact)
				.collect(Collectors.groupingBy(Answer::getRelation,
						LinkedHashMap::new, Collectors.toList()));
		try (Statement ddl = connection.createStatement()) {
			for (Map.Entry<Relation, List<Answer<Relation>>> table : byRelation.entrySet()) {
				ddl.execute("DROP TABLE IF EXISTS " + table.getKey().getName());
				ddl.execute(createTable(table.getKey(), table.getValue().get(0)));
			}
		}
		for (Map.Entry<Relation, List<Answer<Relation>>> table : byRelation.entrySet()) {
			String placeholders = Answers.values(table.getValue().get(0).getReified()).stream()
					.map(v -> "?")
					.collect(Collectors.joining(", "));
			try (
					PreparedStatement insert = connection.prepareStatement(
							"INSERT INTO " + table.getKey().getName() + " VALUES (" + placeholders + ")")
			) {
				for (Answer<Relation> fact : table.getValue()) {
					int column = 1;
					for (Object value : Answers.values(fact.getReified())) {
						insert.setObject(column++, value);
					}
					insert.addBatch();
				}
				insert.executeBatch();
			}
		}
	}

	private static String createTable(Relation relation, Answer<Relation> sample) {
		Property<?>[] columns = relation.getArgs();
		StringBuilder ddl = new StringBuilder("CREATE TABLE ")
				.append(relation.getName()).append("(");
		for (int i = 0; i < columns.length; i++) {
			ddl.append(i == 0 ? "" : ", ")
					.append(columns[i].getName())
					.append(" ").append(sqlType(Answers.values(sample.getReified()).get(i)))
					.append(" NOT NULL");
		}
		return ddl.append(")").toString();
	}

	private static String sqlType(Object value) {
		if (value instanceof Integer) {
			return "INT";
		}
		if (value instanceof Long) {
			return "BIGINT";
		}
		if (value instanceof String) {
			return "VARCHAR(64)";
		}
		throw new IllegalArgumentException("no column type for " + value.getClass());
	}

	@After
	public void closePostgres() throws Exception {
		source.close();
		if (!connection.isClosed()) {
			connection.close();
		}
	}

	/** The exact answers for {@code out}, rendered and sorted. */
	private static List<String> answers(Goal goal, Unifiable<?> out) {
		return goal.solve(out)
				.map(Object::toString)
				.sorted()
				.collect(Collectors.toList());
	}

	/**
	 * The closure of edge over the given backing — a fresh derived relation
	 * per source. Deliberately MIXED consumption: the base case must
	 * ENUMERATE (exists — the generator's choices live in the search tree),
	 * while the recursive conjunct rides posted as a GUARD — sound here
	 * because self's delivery grounds z first and every recursive step has a
	 * single successor, so the collapse fires and the guard discharges into
	 * ground answers. On data whose recursive steps branch, the guard would
	 * park and answers would go conditional.
	 */
	private static Literal reach(AnswerSource backing, Unifiable<Integer> x, Unifiable<Integer> y) {
		return Literal.relation(PostgresFactSourceTest.class, "reachable")
				.arg("src", x)
				.arg("dst", y)
				.solving(edge(backing, x, y)
						.or(defer(() -> {
							Unifiable<Integer> z = lvar();
							return reach(backing, x, z)
									.and(edge(backing, z, y).posted());
						})));
	}

	@Test
	public void aNonrecursiveRelationAnswersIdentically() {
		Unifiable<Integer> viaPg = lvar();
		Unifiable<String> pgName = lvar();
		Unifiable<Integer> viaMemory = lvar();
		Unifiable<String> memoryName = lvar();
		List<String> pg = answers(person(source, viaPg, pgName),
				lval(Tuple.of(viaPg, pgName)));
		List<String> memory = answers(person(reference, viaMemory, memoryName),
				lval(Tuple.of(viaMemory, memoryName)));
		assertThat(pg).isNotEmpty().isEqualTo(memory);
	}

	@Test
	public void aPostedRelationAnswersIdentically() {
		Unifiable<Integer> viaPg = lvar();
		Unifiable<String> pgName = lvar();
		Unifiable<Integer> viaMemory = lvar();
		Unifiable<String> memoryName = lvar();
		List<String> pg = answers(person(source, viaPg, pgName).posted(),
				lval(Tuple.of(viaPg, pgName)));
		List<String> memory = answers(person(reference, viaMemory, memoryName).posted(),
				lval(Tuple.of(viaMemory, memoryName)));
		assertThat(pg).isNotEmpty().isEqualTo(memory);
	}

	@Test
	public void aRecursiveRelationAnswersIdentically() {
		// the diamond closes over PostgreSQL exactly as over memory: the
		// derived closure seals, 1 reaches 4 both ways, duplicates fold
		Unifiable<Integer> pgFrom = lvar();
		Unifiable<Integer> pgTo = lvar();
		Unifiable<Integer> memoryFrom = lvar();
		Unifiable<Integer> memoryTo = lvar();
		List<String> pg = answers(reach(source, pgFrom, pgTo).posted(),
				lval(Tuple.of(pgFrom, pgTo)));
		List<String> memory = answers(reach(reference, memoryFrom, memoryTo).posted(),
				lval(Tuple.of(memoryFrom, memoryTo)));
		assertThat(pg).isNotEmpty().isEqualTo(memory);
	}
}
