package org.clauseway.pldb.sql;

// ABOUTME: The SQL polling source: one pinned connection, the compiler registry,
// ABOUTME: the probe's pattern+region compiled to SELECT..WHERE — every answer a round trip.

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.clauseway.logic.constraints.store.Atom;
import org.clauseway.logic.constraints.store.Theory;
import org.clauseway.logic.finitedomain.FiniteDomainConstraints;
import org.clauseway.logic.nogoods.NogoodConstraints;
import org.clauseway.logic.solving.Residues;
import org.clauseway.logic.solving.Call;
import org.clauseway.logic.unification.terms.Any;
import org.clauseway.logic.unification.terms.Term;
import org.clauseway.pldb.relations.Answer;
import org.clauseway.pldb.relations.Answers;
import org.clauseway.pldb.relations.Literal;
import org.clauseway.pldb.relations.Property;
import org.clauseway.pldb.relations.Relation;
import org.clauseway.pldb.sql.compiler.FiniteDomainSqlCompiler;
import org.clauseway.pldb.sql.compiler.NogoodSqlCompiler;
import org.clauseway.pldb.sql.compiler.SqlPredicate;
import org.clauseway.vavr.Tuple2;
import org.clauseway.vavr.collection.Array;
import org.clauseway.vavr.collection.IndexedSeq;

/**
 * Talks to the backend, nothing else: pins the connection at construction
 * (auto-commit off, {@code REPEATABLE READ} where the driver's metadata
 * admits it, the granted level recorded, the snapshot anchored by a first
 * read), compiles a probe's region through the registered per-family
 * compilers into the WHERE, and executes the SELECT. Holds no pool and no
 * ledger; every get is a round trip, and the estimate is the optimizer
 * barrier. Two compositions sit above it: plain solves take
 * {@link CachingSqlFetch} (the coverage ledger); the certify kinds take
 * this fetch RAW, because there the TRANSACTION's ledger is the cache
 * and a shared row cache under a fresh pin would be data the pin never
 * named.
 */
@Slf4j
public final class SqlFetch implements JdbcSource {

	private final String id;
	@Getter
	private final Connection connection;
	private final int isolation;
	private final Map<Class<?>, SqlCompiler> compilers = new HashMap<>();
	@Getter
	private final Codecs codecs = Codecs.builtin();

	private SqlFetch(String id, Connection connection, int isolation) {
		this.id = id;
		this.connection = connection;
		this.isolation = isolation;
	}

	public static SqlFetch pinned(String id, Connection connection) {
		try {
			connection.setAutoCommit(false);
			// the pin promises AT LEAST a repeatable snapshot: raise a weaker
			// level, keep a stronger one (SERIALIZABLE is the rented certify)
			if (connection.getTransactionIsolation() < Connection.TRANSACTION_REPEATABLE_READ
					&& connection.getMetaData().supportsTransactionIsolationLevel(Connection.TRANSACTION_REPEATABLE_READ)) {
				connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
			}
			// an MVCC snapshot begins at the transaction's FIRST READ, not at
			// setup — without this anchor, "pinned at construction" would
			// silently mean "pinned at the first probe", and writes landing
			// in between would leak into the view
			try (Statement anchor = connection.createStatement()) {
				anchor.execute("SELECT 1");
			}
			return equipped(new SqlFetch(id, connection, connection.getTransactionIsolation()));
		} catch (SQLException e) {
			throw new IllegalStateException("could not pin " + id, e);
		}
	}

	/** The ENGINE-CORE compilers as equipment — the FD family pushes out of the box. */
	private static SqlFetch equipped(SqlFetch fetch) {
		fetch.compiling(FiniteDomainConstraints.class, new FiniteDomainSqlCompiler());
		fetch.compiling(NogoodConstraints.class, new NogoodSqlCompiler(fetch.compilers()));
		return fetch;
	}

	/**
	 * The LIVE lane: no snapshot, no isolation raise — auto-commit on, so
	 * every statement reads the CURRENT committed world. Read stability
	 * must come from elsewhere (the caching ledger); what makes a
	 * transaction over this lane sound is per-touch pins captured BEFORE
	 * their rows and the commit-time proof. No long-lived transaction is
	 * ever held — idle-in-transaction cannot occur.
	 */
	public static SqlFetch live(String id, Connection connection) {
		try {
			connection.setAutoCommit(true);
			return equipped(new SqlFetch(id, connection, connection.getTransactionIsolation()));
		} catch (SQLException e) {
			throw new IllegalStateException("could not open live " + id, e);
		}
	}

	int isolation() {
		return isolation;
	}

	@Override
	public String id() {
		return id;
	}

	/** Registers the family's WHERE compiler; may OVERRIDE a built-in. */
	public void compiling(Class<?> family, SqlCompiler compiler) {
		compilers.put(family, compiler);
	}

	/** Binds column codecs through a template literal. */
	public SqlFetch withCodec(Literal template) {
		codecs.withCodec(template);
		return this;
	}

	/** The LIVE registry view — cross-family compilers (nogoods) delegate through it. */
	Map<Class<?>, SqlCompiler> compilers() {
		return compilers;
	}

	@Override
	public synchronized Iterable<Answer> answers(Call<Relation> probe) {
		Relation relation = probe.getRelation();
		IndexedSeq<Term<Object>> args = Array.ofAll(Answers.positions(probe.getArguments()));
		List<Answer> answers = new ArrayList<>();
		answers.addAll(rows(relation, args, push(relation, args, probe.getResidues())));
		return answers;
	}

	@Override
	public long estimate(Call<Relation> probe) {
		return Long.MAX_VALUE;
	}

	@Override
	public void close() {
		try {
			if (!connection.getAutoCommit()) {
				connection.rollback();
			}
			connection.close();
		} catch (SQLException e) {
			throw new IllegalStateException("could not close " + id, e);
		}
	}

	/** Every registered family's atoms through its compiler; misses stay local. */
	private List<SqlPredicate> push(Relation relation, IndexedSeq<Term<Object>> args, Residues region) {
		List<SqlPredicate> predicates = new ArrayList<>();
		SqlCompiler.ColumnResolver resolver = columnResolver(relation, args);
		for (Tuple2<Class<?>, Theory<?>> family : region.getTheories()) {
			SqlCompiler compiler = compilers.get(family._1);
			if (compiler == null) {
				continue;
			}
			for (Atom<?> atom : family._2.atoms()) {
				compiler.compile(atom, resolver)
						.ifPresent(predicates::add);
			}
		}
		return predicates;
	}

	/**
	 * An atom's name resolves to the FIRST column it occupies in the
	 * image (Any equality is by value, so the term matches its own
	 * occurrences). Only variables name columns. A COUPLED variable —
	 * two occurrences — resolves to its first: every valid row satisfies
	 * the coupling, so the constraint at any one occurrence is implied
	 * and can never exclude a valid row; the other occurrences add no
	 * semantic narrowing (rows disagreeing between them are coupling-
	 * invalid and die at the local restate, as the unpushed coupling
	 * itself already does).
	 */
	private static SqlCompiler.ColumnResolver columnResolver(Relation relation, IndexedSeq<Term<Object>> args) {
		return new SqlCompiler.ColumnResolver() {
			@Override
			public Optional<String> columnOf(Term<?> term) {
				return columnsOf(term).stream().findFirst();
			}

			@Override
			public List<String> columnsOf(Term<?> term) {
				if (!(term instanceof Any)) {
					return Collections.emptyList();
				}
				return IntStream.range(0, args.length())
						.filter(i -> term.equals(args.get(i)))
						.mapToObj(i -> relation.getArgs()[i].getName())
						.collect(Collectors.toList());
			}

			@Override
			public boolean nullable(String column) {
				for (Property<?> property : relation.getArgs()) {
					if (property.getName().equals(column)) {
						return property.isNullable();
					}
				}
				return false;
			}
		};
	}

	/** The probe's region as SQL — the certify side's door to the one rendering. */
	RegionSql region(Call<Relation> probe) {
		Relation relation = probe.getRelation();
		IndexedSeq<Term<Object>> args = Array.ofAll(Answers.positions(probe.getArguments()));
		return regionSql(relation, args, push(relation, args, probe.getResidues()));
	}

	private RegionSql regionSql(Relation relation, IndexedSeq<Term<Object>> args, List<SqlPredicate> predicates) {
		Property<?>[] columns = relation.getArgs();
		List<String> conditions = new ArrayList<>();
		List<Object> parameters = new ArrayList<>();
		for (int i = 0; i < args.size(); i++) {
			if (args.get(i).isVal()) {
				if (args.get(i).get() == null) {
					requireNullable(relation, columns[i]);
					conditions.add(columns[i].getName() + " IS NULL");
				} else {
					conditions.add(columns[i].getName() + " = ?");
					parameters.add(codecs.encode(relation, columns[i], args.get(i).get()));
				}
			}
		}
		for (SqlPredicate predicate : predicates) {
			conditions.add(predicate.getFragment());
			predicate.getParameters().forEach(parameters::add);
		}
		return new RegionSql(conditions, parameters);
	}

	private List<Answer> rows(Relation relation, IndexedSeq<Term<Object>> args, List<SqlPredicate> predicates) {
		Property<?>[] columns = relation.getArgs();
		List<String> unboundColumns = new ArrayList<>();
		List<Property<?>> unboundProperties = new ArrayList<>();
		for (int i = 0; i < args.size(); i++) {
			if (!args.get(i).isVal()) {
				unboundColumns.add(columns[i].getName());
				unboundProperties.add(columns[i]);
			}
		}
		RegionSql region = regionSql(relation, args, predicates);
		// every position bound: nothing to project, the probe is an existence
		// check — a blank select list would not compile
		String sql = "SELECT " + (unboundColumns.isEmpty() ? "1" : String.join(", ", unboundColumns))
				+ " FROM " + relation.getName() + region.whereClause();
		if (log.isDebugEnabled()) {
			log.debug("{}: {} ← {}", id, sql, region.getParameters());
		}
		try (PreparedStatement statement = connection.prepareStatement(sql)) {
			int index = 1;
			for (Object parameter : region.getParameters()) {
				statement.setObject(index++, parameter);
			}
			try (ResultSet rows = statement.executeQuery()) {
				List<Answer> facts = new ArrayList<>();
				while (rows.next()) {
					Object[] values = new Object[unboundColumns.size()];
					for (int i = 0; i < values.length; i++) {
						values[i] = rows.getObject(unboundColumns.get(i));
						if (values[i] != null) {
							values[i] = codecs.decode(relation, unboundProperties.get(i), values[i]);
						}
						if (values[i] == null && !unboundProperties.get(i).isNullable()) {
							throw new IllegalStateException(relation.getName() + ": column '"
									+ unboundColumns.get(i) + "' holds null and is not"
									+ " declared nullable()");
						}
					}
					Array<Object> vals = mergeValuesWithSupplied(args, values);
					facts.add(Answers.answer(relation, vals.toJavaList()));
				}
				return facts;
			}
		} catch (SQLException e) {
			throw new IllegalStateException("fetch failed on " + id + ": " + sql, e);
		}
	}

	private static Array<Object> mergeValuesWithSupplied(IndexedSeq<Term<Object>> args, Object[] values) {
		int i = 0, j = 0;
		Array<Object> result = Array.empty();
		while (i + j < args.length()) {
			if (args.get(i + j).isVal()) {
				result = result.append(args.get(i + j).get());
				++i;
			} else {
				result = result.append(values[j]);
				++j;
			}
		}
		return result;
	}

	private static void requireNullable(Relation relation, Property<?> column) {
		if (!column.isNullable()) {
			throw new IllegalStateException(relation.getName() + ": column '"
					+ column.getName() + "' is not nullable — null cannot probe it");
		}
	}

}
