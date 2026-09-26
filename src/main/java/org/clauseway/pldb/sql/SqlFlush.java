package org.clauseway.pldb.sql;

// ABOUTME: The JDBC write face: asserted facts land as INSERTs, retracted facts
// ABOUTME: leave as by-fact DELETEs — one schema convention, one codec registry.

import org.clauseway.pldb.relations.Answers;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.clauseway.pldb.Writer;
import org.clauseway.logic.solving.Answer;
import org.clauseway.pldb.relations.Literal;
import org.clauseway.pldb.relations.Property;
import org.clauseway.pldb.relations.Relation;
import org.clauseway.vavr.collection.Array;

/**
 * Flushes facts through one JDBC connection by the same convention the
 * fetch reads with: the relation's name is the table, its property names
 * are the columns. Every cell encodes through the CODEC registry — a
 * value without a codec refuses by relation and column, and a
 * structural value (a collection, a term) keeps the modelling refusal:
 * its relational spelling is a child relation. The whole batch encodes
 * before any row lands. The caller owns the transaction: this face
 * neither commits nor rolls back.
 */

@Slf4j
@Value
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public class SqlFlush {
	Connection connection;
	Codecs codecs;
	String stampColumn;
	Object stampValue;

	public static SqlFlush over(Connection connection) {
		return new SqlFlush(connection, Codecs.builtin(), null, null);
	}

	public static SqlFlush over(Connection connection, Codecs codecs) {
		return new SqlFlush(connection, codecs, null, null);
	}

	/**
	 * Every inserted row additionally carries {@code column = value} — the
	 * version stamp of a certify kind; the column is the backend's private
	 * surface, invisible to the schema.
	 */
	public SqlFlush stamped(String column, Object value) {
		return new SqlFlush(connection, codecs, column, value);
	}

	/** The statement face: literals convert at the threshold, holes refuse. */
	public void flush(Collection<Literal> statements) {
		flush(Writer.facts(statements));
	}

	/** The statement face of the removal lane. */
	public void delete(Collection<Literal> statements) {
		delete(Writer.facts(statements));
	}

	public void flush(List<Answer<Relation>> rows) {
		try {
			for (Map.Entry<Relation, List<Row>> table : encodedByRelation(rows).entrySet()) {
				insert(table.getKey(), table.getValue());
			}
		} catch (SQLException e) {
			throw new IllegalStateException("flush failed: " + e.getMessage(), e);
		}
	}

	/** The row beneath both doors: a relation and its encoded cells. */
	@Value
	private static class Row {
		Relation relation;
		Array<Object> cells;
	}

	private Map<Relation, List<Row>> encodedByRelation(List<Answer<Relation>> rows) {
		return rows.stream()
				.map(this::encoded)
				.collect(Collectors.groupingBy(Row::getRelation, LinkedHashMap::new, Collectors.toList()));
	}

	private void insert(Relation relation, List<Row> rows) throws SQLException {
		String sql = insertSql(relation);
		if (log.isDebugEnabled()) {
			for (Row row : rows) {
				log.debug("{} ← {}{}", sql, row.getCells(),
						stampColumn == null ? "" : ", " + stampValue);
			}
		}
		try (PreparedStatement statement = connection.prepareStatement(sql)) {
			for (Row row : rows) {
				for (int i = 0; i < row.getCells().size(); i++) {
					statement.setObject(i + 1, row.getCells().get(i));
				}
				if (stampColumn != null) {
					statement.setObject(row.getCells().size() + 1, stampValue);
				}
				statement.addBatch();
			}
			statement.executeBatch();
		}
	}

	/**
	 * The removal lane: BY FACT — the WHERE matches every declared column
	 * (encoded through the same registry the insert used; a null cell
	 * compares as IS NULL), so every physical duplicate leaves, whatever
	 * private stamp it carries — the version column is deliberately not
	 * in the predicate. Batched per (relation, null-shape); the caller
	 * owns the transaction.
	 */
	public void delete(List<Answer<Relation>> rows) {
		try {
			for (Map.Entry<Relation, List<Row>> table : encodedByRelation(rows).entrySet()) {
				Map<String, List<Row>> byShape = table.getValue().stream()
						.collect(Collectors.groupingBy(SqlFlush::nullShape,
								LinkedHashMap::new, Collectors.toList()));
				for (List<Row> shape : byShape.values()) {
					delete(table.getKey(), shape);
				}
			}
		} catch (SQLException e) {
			throw new IllegalStateException("delete failed: " + e.getMessage(), e);
		}
	}

	private void delete(Relation relation, List<Row> rows) throws SQLException {
		Row shape = rows.get(0);
		String sql = deleteSql(relation, shape);
		if (log.isDebugEnabled()) {
			for (Row row : rows) {
				log.debug("{} ← {}", sql, row.getCells());
			}
		}
		try (PreparedStatement statement = connection.prepareStatement(sql)) {
			for (Row row : rows) {
				int hole = 1;
				for (int i = 0; i < row.getCells().size(); i++) {
					Object value = row.getCells().get(i);
					if (value != null) {
						statement.setObject(hole++, value);
					}
				}
				statement.addBatch();
			}
			statement.executeBatch();
		}
	}

	private static String deleteSql(Relation relation, Row shape) {
		StringBuilder where = new StringBuilder();
		for (int i = 0; i < relation.getArgs().length; i++) {
			if (where.length() > 0) {
				where.append(" AND ");
			}
			where.append(relation.getArgs()[i].getName())
					.append(shape.getCells().get(i) == null ? " IS NULL" : " = ?");
		}
		return "DELETE FROM " + relation.getName() + " WHERE " + where;
	}

	/** Which cells are null decides the statement's shape, not its binds. */
	private static String nullShape(Row row) {
		StringBuilder shape = new StringBuilder();
		for (int i = 0; i < row.getCells().size(); i++) {
			shape.append(row.getCells().get(i) == null ? '0' : '1');
		}
		return shape.toString();
	}

	private String insertSql(Relation relation) {
		String columns = Arrays.stream(relation.getArgs())
				.map(Property::getName)
				.collect(Collectors.joining(", "));
		String holes = Arrays.stream(relation.getArgs())
				.map(p -> "?")
				.collect(Collectors.joining(", "));
		if (stampColumn != null) {
			columns = columns + ", " + stampColumn;
			holes = holes + ", ?";
		}
		return "INSERT INTO " + relation.getName() + " (" + columns + ") VALUES (" + holes + ")";
	}

	/**
	 * Every cell through the registry BEFORE any insert — the whole batch
	 * validates or none of it lands. A null cell rides the nullable
	 * declaration; everything else must have a codec.
	 */
	private Row encoded(Answer<Relation> fact) {
		Property<?>[] columns = fact.getRelation().getArgs();
		List<Object> values = Answers.values(fact.getReified());
		Object[] cells = new Object[columns.length];
		for (int i = 0; i < columns.length; i++) {
			Object value = values.get(i);
			if (value == null) {
				if (!columns[i].isNullable()) {
					throw new IllegalStateException("flush of " + fact.getRelation().getName()
							+ ": column '" + columns[i].getName() + "' is not nullable —"
							+ " null cannot land in it");
				}
				continue;
			}
			cells[i] = codecs.encode(fact.getRelation(), columns[i], value);
		}
		return new Row(fact.getRelation(), Array.of(cells));
	}
}
