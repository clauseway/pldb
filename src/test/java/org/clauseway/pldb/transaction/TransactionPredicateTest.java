package org.clauseway.pldb.transaction;

// ABOUTME: The conflict predicate seam: a recognized commit failure maps to
// ABOUTME: Conflict, an unrecognized one surfaces as itself.

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.clauseway.logic.unification.terms.LVal.lval;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Collections;
import org.clauseway.logic.unification.terms.Unifiable;
import org.clauseway.pldb.AnswerSource;
import org.clauseway.pldb.relations.External;
import org.clauseway.pldb.relations.Literal;
import org.clauseway.pldb.relations.Schema;
import org.clauseway.pldb.sql.SerializableSource;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class TransactionPredicateTest {

	private Connection connection;

	@Before
	public void openDatabase() throws SQLException {
		connection = DriverManager.getConnection("jdbc:h2:mem:");
	}

	@After
	public void closeDatabase() throws SQLException {
		connection.close();
	}

	private static External<?> orphan(AnswerSource db, Unifiable<Integer> id) {
		return Literal.relation(TransactionPredicateTest.class, "orphan").args(Schema.builder().arg("id", id).build()).from(db);
	}

	/** No orphan table exists, so the flush inside commit fails for real. */
	private Throwable commitFailure(Transaction db) {
		return catchThrowable(() -> db.asserting(Collections.singletonList(orphan(null, lval(1))))
				.commit());
	}

	@Test
	public void aRecognizedCommitFailureMapsToConflict() {
		Throwable refused = commitFailure(Transaction.over(SerializableSource.pinned("h2", connection, e -> true)));
		assertThat(refused).isInstanceOf(Transaction.Conflict.class);
	}

	@Test
	public void anUnrecognizedFailureSurfacesAsItself() {
		Throwable refused = commitFailure(Transaction.over(SerializableSource.pinned("h2", connection, e -> false)));
		assertThat(refused).isNotNull().isNotInstanceOf(Transaction.Conflict.class);
	}
}
