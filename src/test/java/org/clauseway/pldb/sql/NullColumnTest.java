package org.clauseway.pldb.sql;

// ABOUTME: The null catcher: a NULL cell in a column the schema did not declare
// ABOUTME: nullable refuses loudly by relation and column, never a silent broken fact.

import org.clauseway.logic.solving.Query;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.clauseway.logic.unification.terms.LVar.lvar;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.stream.Collectors;
import org.clauseway.logic.unification.terms.Unifiable;
import org.clauseway.pldb.AnswerSource;
import org.clauseway.pldb.relations.Literal;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class NullColumnTest {

	private Connection connection;

	@Before
	public void openDatabase() throws SQLException {
		connection = DriverManager.getConnection("jdbc:h2:mem:");
		try (Statement ddl = connection.createStatement()) {
			ddl.execute("CREATE TABLE person(id INT NOT NULL, name VARCHAR(64))");
			ddl.execute("INSERT INTO person VALUES (1, 'Ada'), (2, NULL)");
		}
	}

	@After
	public void closeDatabase() throws SQLException {
		connection.close();
	}

	private static Literal person(AnswerSource db, Unifiable<Integer> id, Unifiable<String> name) {
		return Literal.relation(NullColumnTest.class, "person")
				.arg("id", id).indexed()
				.arg("name", name)
				.from(db);
	}

	@Test
	public void aNullCellInAnUndeclaredColumnRefusesByName() throws Exception {
		try (CachingSqlFetch source = CachingSqlFetch.pinned("h2", connection)) {
			Unifiable<String> name = lvar();
			assertThatThrownBy(() -> Query.of(person(source, lvar(), name)).solve(name)
					.collect(Collectors.toList()))
					.isInstanceOf(IllegalStateException.class)
					.hasMessageContaining("person")
					.hasMessageContaining("name")
					.hasMessageContaining("null");
		}
	}
}
