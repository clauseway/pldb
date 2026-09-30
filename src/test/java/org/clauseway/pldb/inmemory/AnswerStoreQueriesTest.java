package org.clauseway.pldb.inmemory;

// ABOUTME: The store as a genealogy database: joins, recursion through condu and
// ABOUTME: matche, and optimizer-driven queries all read through the Call probe.

import org.clauseway.logic.solving.Query;
import static org.assertj.core.api.Assertions.assertThat;
import static org.clauseway.logic.goals.Goal.condu;
import static org.clauseway.logic.goals.Goal.defer;
import static org.clauseway.logic.goals.Logic.distincto;
import static org.clauseway.logic.goals.Matche.llist;
import static org.clauseway.logic.goals.Matche.matche;
import static org.clauseway.logic.nogoods.Exclusion.exclude;
import static org.clauseway.logic.unification.terms.LVal.lval;
import static org.clauseway.logic.unification.terms.LVar.lvar;

import java.util.Arrays;
import java.util.List;
import java.util.function.BiFunction;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import org.clauseway.functional.tuples.Tuple;
import org.clauseway.functional.tuples.Tuple2;
import org.clauseway.logic.goals.Goal;
import org.clauseway.logic.goals.Logic;
import org.clauseway.logic.goals.optimizer.CascadingOptimizer;
import org.clauseway.logic.unification.structures.LList;
import org.clauseway.logic.unification.terms.Term;
import org.clauseway.logic.unification.terms.Unifiable;
import org.clauseway.pldb.AnswerSource;
import org.clauseway.pldb.relations.Literal;
import org.junit.Test;

public class AnswerStoreQueriesTest {

	private static Literal person(AnswerSource db, Unifiable<Integer> id, Unifiable<String> name, Unifiable<String> surname, Unifiable<Gender> gender) {
		return Literal.relation(AnswerStoreQueriesTest.class, "person")
				.arg("id", id).indexed()
				.arg("name", name).indexed()
				.arg("surname", surname)
				.arg("gender", gender)
				.from(db);
	}

	private static Literal parent(AnswerSource db, Unifiable<Integer> parentId, Unifiable<Integer> childId) {
		return Literal.relation(AnswerStoreQueriesTest.class, "parent")
				.arg("parentId", parentId).indexed()
				.arg("childId", childId).indexed()
				.from(db);
	}

	private enum Gender {
		MALE, FEMALE
	}

	private static AnswerStore loadGeneology(AnswerStore db) {
		return db.asserting(Arrays.asList(
						person(null, lval(1), lval("Michał"), lval("Gac"), lval(Gender.MALE)),
						person(null, lval(2), lval("Franciszek"), lval("Żyduch"), lval(Gender.MALE)),
						person(null, lval(3), lval("Czesław"), lval("Kroc"), lval(Gender.MALE)),
						person(null, lval(4), lval("Wacław"), lval("Wiercioch"), lval(Gender.MALE)),
						person(null, lval(5), lval("Wiesław"), lval("Gac"), lval(Gender.MALE)),
						person(null, lval(6), lval("Ireneusz"), lval("Kroc"), lval(Gender.MALE)),
						person(null, lval(7), lval("Michał"), lval("Gac"), lval(Gender.MALE)),
						person(null, lval(8), lval("Tomek"), lval("Gac"), lval(Gender.MALE)),
						person(null, lval(10), lval("Aniela"), lval("X"), lval(Gender.FEMALE)),
						person(null, lval(11), lval("Honorata"), lval("Żyduch"), lval(Gender.FEMALE)),
						person(null, lval(12), lval("Helena"), lval("Gac"), lval(Gender.FEMALE)),
						person(null, lval(13), lval("Ewa"), lval("Kroc"), lval(Gender.FEMALE)),
						person(null, lval(14), lval("Janina"), lval("Wiercioch"), lval(Gender.FEMALE)),
						person(null, lval(15), lval("Arletta"), lval("Gac"), lval(Gender.FEMALE)),
						person(null, lval(16), lval("Jolanta"), lval("Kroc"), lval(Gender.FEMALE)),
						person(null, lval(17), lval("Henryka"), lval("Gac"), lval(Gender.FEMALE)),
						person(null, lval(18), lval("Kasia"), lval("Gac"), lval(Gender.FEMALE)),
						person(null, lval(19), lval("Marta"), lval("Gac"), lval(Gender.FEMALE)),
						person(null, lval(20), lval("Magda"), lval("Gac"), lval(Gender.FEMALE)),
						person(null, lval(21), lval("Weronika"), lval("Kroc"), lval(Gender.FEMALE)),
						person(null, lval(22), lval("Monika"), lval("Kroc"), lval(Gender.FEMALE))))
				.asserting(Arrays.asList(
						parent(null, lval(10), lval(11)),
						parent(null, lval(1), lval(5)),
						parent(null, lval(12), lval(5)),
						parent(null, lval(5), lval(7)),
						parent(null, lval(5), lval(18)),
						parent(null, lval(5), lval(20)),
						parent(null, lval(17), lval(18)),
						parent(null, lval(17), lval(7)),
						parent(null, lval(5), lval(8)),
						parent(null, lval(16), lval(19)),
						parent(null, lval(11), lval(15)),
						parent(null, lval(2), lval(15)),
						parent(null, lval(15), lval(8)),
						parent(null, lval(15), lval(20)),
						parent(null, lval(3), lval(6)),
						parent(null, lval(13), lval(6)),
						parent(null, lval(14), lval(16)),
						parent(null, lval(4), lval(16)),
						parent(null, lval(6), lval(19)),
						parent(null, lval(6), lval(22)),
						parent(null, lval(22), lval(21))));
	}

	private static final AnswerStore db = loadGeneology(AnswerStore.empty());

	@Test
	public void shouldFindGrandparents() {
		Unifiable<String> gpName = lvar();
		Unifiable<String> gpSurname = lvar();

		List<String> result =
				Query.of(Logic.<Integer, Integer, Integer> exist((gpId, parentId, childId) ->
								person(db, childId, lval("Tomek"), lvar(), lvar())
										.and(parent(db, parentId, childId))
										.and(parent(db, gpId, parentId))
										.and(person(db, gpId, gpName, gpSurname, lvar())))).solve(lval(Tuple.of(gpName, gpSurname)))
						.map(Term::get)
						.map(AnswerStoreQueriesTest::concatNameAndSurname)
						.distinct()
						.collect(Collectors.toList());
		assertThat(result)
				.containsExactlyInAnyOrder(
						"Franciszek Żyduch",
						"Michał Gac",
						"Helena Gac",
						"Honorata Żyduch");
	}

	@Test
	public void shouldFindParentSpouse() {
		Unifiable<String> spouseName = lvar();
		Unifiable<String> spouseSurname = lvar();

		assertThat(
				Query.of(Logic.<Integer, Integer, Integer> exist(
								(fatherId, childId, motherId) ->
										person(db, fatherId, lval("Wiesław"), lvar(), lval(Gender.MALE))
												.and(parent(db, fatherId, childId),
														parent(db, motherId, childId),
														person(db, motherId, spouseName, spouseSurname, lval(Gender.FEMALE))))).solve(lval(Tuple.of(spouseName, spouseSurname)))
						.distinct()
						.map(Term::get)
						.map(AnswerStoreQueriesTest::concatNameAndSurname))
				.containsExactlyInAnyOrder("Arletta Gac", "Henryka Gac");
	}

	static Goal ancestors(Unifiable<Integer> descendant, Unifiable<LList<Integer>> ancestors) {
		return Logic.<Integer, LList<Integer>> exist((parentId, rest) ->
				parent(db, parentId, descendant)
						.and(ancestors.unifies(LList.of(parentId, rest)))
						.and(condu(defer(() -> ancestors(parentId, rest)),
								rest.unifies(LList.empty()))));
	}

	public static BiFunction<Unifiable<Integer>,
			Unifiable<Tuple2<Unifiable<String>, Unifiable<String>>>,
			Goal> personWithIdNameAndSurname(AnswerSource db) {
		return (id, data) -> Logic.<String, String> exist((name, surname) ->
				data.unifies(Tuple.of(name, surname))
						.and(person(db, id, name, surname, lvar())));
	}

	@Test
	public void shouldFindAncestors() {
		Unifiable<LList<Tuple2<Unifiable<String>, Unifiable<String>>>> ancestorNames = lvar();

		List<List<String>> result = Query.of(Logic.<Integer, LList<Integer>> exist((descendantId, l) ->
						person(db, descendantId, lval("Tomek"), lvar(), lvar())
								.and(ancestors(descendantId, l))
								.and(LList.map(l, ancestorNames, personWithIdNameAndSurname(db))))).solve(ancestorNames)
				.map(AnswerStoreQueriesTest::unwrap)
				.map(AnswerStoreQueriesTest::concatNameAndSurname)
				.collect(Collectors.toList());

		assertThat(result)
				.containsExactlyInAnyOrder(
						Arrays.asList("Wiesław Gac", "Helena Gac"),
						Arrays.asList("Arletta Gac", "Franciszek Żyduch"),
						Arrays.asList("Wiesław Gac", "Michał Gac"),
						Arrays.asList("Arletta Gac", "Honorata Żyduch", "Aniela X"));

	}

	static Goal line(Unifiable<Integer> ancestor, Unifiable<LList<Integer>> line, Unifiable<Integer> descendant) {
		return matche(line,
				llist(() -> parent(db, ancestor, descendant)),
				llist((head, tail) ->
						parent(db, ancestor, head)
								.and(defer(() -> line(head, tail, descendant)))));
	}

	@Test
	public void shouldFindLine2() {
		Unifiable<LList<Tuple2<Unifiable<String>, Unifiable<String>>>> line = lvar();
		List<List<String>> result = Query.of(Logic.<LList<Integer>, Integer, Integer> exist(
						(l, descendantId, ancestorId) ->
								person(db, ancestorId, lval("Aniela"), lvar(), lvar())
										.and(person(db, descendantId, lval("Tomek"), lvar(), lvar()),
												line(ancestorId, l, descendantId),
												LList.map(l, line, personWithIdNameAndSurname(db))))).solve(line)
				.map(AnswerStoreQueriesTest::unwrap)
				.map(AnswerStoreQueriesTest::concatNameAndSurname)
				.collect(Collectors.toList());
		assertThat(result)
				.containsExactly(Arrays.asList("Honorata Żyduch", "Arletta Gac"));
	}

	static Goal relativesImpl(Unifiable<Integer> lhs,
			Unifiable<Integer> rhs,
			Unifiable<LList<Integer>> line,
			Unifiable<LList<Integer>> checked) {
		return distincto(checked)
				.and(matche(line,
						llist(() -> parent(db, lhs, rhs)),
						llist((lineHead, lineTail) ->
								parent(db, lhs, lineHead)
										.or(parent(db, lineHead, lhs))
										.and(defer(() -> relativesImpl(lineHead, rhs, lineTail,
												LList.of(rhs, LList.of(lineHead, checked)))))
										.and(distincto(line)))));
	}

	static Goal relatives(Unifiable<Integer> lhs, Unifiable<Integer> rhs, Unifiable<LList<Integer>> line) {
		return relativesImpl(rhs, lhs, line, LList.empty());
	}

	@Test
	public void shouldFindRelatives() {
		Unifiable<LList<Tuple2<Unifiable<String>, Unifiable<String>>>> line = lvar();
		List<List<String>> result = Query.of(Logic.<LList<Integer>, Integer, Integer> exist(
						(l, lhsId, rhsId) ->
								exclude(lhsId.unifies(rhsId))
										.and(person(db, rhsId, lval("Tomek"), lvar(), lvar()))
										.and(person(db, lhsId, lval("Magda"), lvar(), lvar()),
												relatives(rhsId, lhsId, l),
												Logic.<LList<Integer>> exist(res ->
														Logic.appendo(LList.of(rhsId, l),
																		LList.of(lhsId),
																		res)
																.and(LList.map(res, line, personWithIdNameAndSurname(db)))))
										.accept(new CascadingOptimizer()).ground())).solve(line)
				.map(AnswerStoreQueriesTest::unwrap)
				.map(AnswerStoreQueriesTest::concatNameAndSurname)
				.collect(Collectors.toList());
		assertThat(result)
				.containsExactlyInAnyOrder(
						Arrays.asList("Tomek Gac", "Arletta Gac", "Magda Gac"),
						Arrays.asList("Tomek Gac", "Wiesław Gac", "Magda Gac"));
	}

	private static List<String> concatNameAndSurname(Iterable<Tuple2<Unifiable<String>, Unifiable<String>>> ll) {
		return StreamSupport.stream(ll.spliterator(), false)
				.map(AnswerStoreQueriesTest::concatNameAndSurname)
				.collect(Collectors.toList());
	}

	private static String concatNameAndSurname(Tuple2<Unifiable<String>, Unifiable<String>> t) {
		return t.map(Term::get, Term::get)
				.map2(" "::concat)
				.apply(String::concat);
	}

	private static <T> List<T> unwrap(Term<LList<T>> ll) {
		return ll.get()
				.toValueStream()
				.collect(Collectors.toList());
	}
}
