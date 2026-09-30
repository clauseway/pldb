package org.clauseway.pldb.transaction;

// ABOUTME: The Transaction over the rented certify tier on real PostgreSQL: staged
// ABOUTME: facts land at commit, abandonment leaves no trace, SSI maps skew to Conflict.

import org.clauseway.logic.solving.Query;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.clauseway.logic.unification.terms.LVal.lval;
import static org.clauseway.logic.unification.terms.LVar.lvar;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import org.clauseway.logic.unification.terms.Unifiable;
import org.clauseway.pldb.AnswerSource;
import org.clauseway.pldb.relations.Literal;
import org.clauseway.pldb.sql.SerializableSource;
import org.junit.AfterClass;
import org.junit.Assume;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

public class TransactionPostgresTest {

	private static PostgreSQLContainer<?> postgres;

	@BeforeClass
	public static void startPostgres() {
		Assume.assumeTrue("Docker unavailable — the commit receipts need it",
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
	public void resetSchema() throws SQLException {
		try (Connection admin = connect(); Statement ddl = admin.createStatement()) {
			ddl.execute("DROP TABLE IF EXISTS person");
			ddl.execute("CREATE TABLE person(id INT NOT NULL, name VARCHAR(64) NOT NULL)");
			admin.commit();
		}
	}

	private static Connection connect() throws SQLException {
		Connection connection = DriverManager.getConnection(
				postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
		connection.setAutoCommit(false);
		return connection;
	}

	private static Literal person(AnswerSource db, Unifiable<Integer> id, Unifiable<String> name) {
		return Literal.relation(TransactionPostgresTest.class, "person")
				.arg("id", id).indexed()
				.arg("name", name)
				.from(db);
	}

	private static List<String> names(AnswerSource db) {
		Unifiable<String> name = lvar();
		return Query.of(person(db, lvar(), name)).solve(name)
				.map(Object::toString)
				.sorted()
				.collect(Collectors.toList());
	}

	private static Transaction transaction(String id, Connection connection) {
		return AbstractTransaction.over(SerializableSource.postgres(id, connection));
	}

	@Test
	public void openGrantsSerializable() throws Exception {
		Connection connection = connect();
		try (Transaction db = transaction("pg", connection)) {
			assertThat(connection.getTransactionIsolation())
					.isEqualTo(Connection.TRANSACTION_SERIALIZABLE);
		}
	}

	@Test
	public void commitLandsStagedFactsForTheNextTransaction() throws Exception {
		Transaction writer = transaction("pg", connect())
				.asserting(Arrays.asList(
						person(null, lval(1), lval("Ada")),
						person(null, lval(2), lval("Alan"))));
		assertThat(names(writer))
				.describedAs("the writer reads its own staged rows before commit")
				.containsExactly("{Ada}", "{Alan}");
		writer.commit();

		try (Transaction reader = transaction("pg", connect())) {
			assertThat(names(reader)).containsExactly("{Ada}", "{Alan}");
		}
	}

	@Test
	public void anAbandonedValueLeavesNoTrace() throws Exception {
		try (
				Transaction abandoned = transaction("pg", connect())
						.asserting(Collections.singletonList(person(null, lval(1), lval("Ada"))))
		) {
			assertThat(names(abandoned)).containsExactly("{Ada}");
		}
		try (Transaction reader = transaction("pg", connect())) {
			assertThat(names(reader)).isEmpty();
		}
	}

	@Test
	public void aGuardedConcurrentAppendMeetsTheConflict() throws Exception {
		// write skew, certified by rented SSI: both values read the person
		// region their sibling writes; the first commit wins, the second maps
		// to Conflict — the caller's move is an ordinary re-solve
		Transaction first = transaction("pg-first", connect());
		Transaction second = transaction("pg-second", connect());
		assertThat(names(first)).isEmpty();
		assertThat(names(second)).isEmpty();

		first = first.asserting(Collections.singletonList(
				person(null, lval(1), lval("Ada"))));
		second = second.asserting(Collections.singletonList(
				person(null, lval(2), lval("Alan"))));

		first.commit();
		Transaction loser = second;
		assertThatThrownBy(loser::commit).isInstanceOf(Transaction.Conflict.class);

		try (Transaction reader = transaction("pg", connect())) {
			assertThat(names(reader))
					.describedAs("only the winner's row landed")
					.containsExactly("{Ada}");
		}
	}
}
