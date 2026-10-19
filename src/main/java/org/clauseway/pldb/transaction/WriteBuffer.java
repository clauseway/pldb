package org.clauseway.pldb.transaction;

// ABOUTME: A frozen base plus a private SIGNED delta — staged assertions and staged
// ABOUTME: retractions — read as one source with the value semantics of a store.

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.Value;
import org.clauseway.functional.Exceptions;
import org.clauseway.logic.solving.JoinMap;
import org.clauseway.logic.solving.Condition;
import org.clauseway.logic.solving.Call;
import org.clauseway.logic.unification.terms.Reified;
import org.clauseway.pldb.AnswerSource;
import org.clauseway.pldb.inmemory.AnswerStore;
import org.clauseway.logic.solving.Answer;
import org.clauseway.pldb.relations.Answers;
import org.clauseway.pldb.relations.Relation;
import org.clauseway.vavr.collection.Array;

/**
 * An immutable store value over a shared read-only base: reads union the
 * base with this value's staged assertions MINUS its staged retractions,
 * and every write mints a new value — ancestors keep answering as
 * before, siblings fork legally. The two staged lists (order preserved)
 * are what a commit face lands; until then the base never learns of
 * either. The delta is a signed set: a write that meets its own opposite
 * — retracting a fact this value staged, asserting one it staged to
 * remove — cancels it, so replacing a fact within the transaction that
 * staged it leaves only the replacement, and what remains is the delta
 * against the base. The DOMAIN's no-delete doctrine lives above this type,
 * at the facades that choose not to expose the retracting door.
 */
@Value
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
class WriteBuffer implements AnswerSource {
	AnswerSource base;
	AnswerStore insertions;
	AnswerStore removals;
	Array<Answer<Relation>> stagedAssertions;
	Array<Answer<Relation>> stagedRetractions;

	public static WriteBuffer over(AnswerSource base) {
		return new WriteBuffer(base, AnswerStore.empty(), AnswerStore.empty(),
				Array.empty(), Array.empty());
	}

	public WriteBuffer asserting(List<Answer<Relation>> rows) {
		List<Answer<Relation>> cancelled = staged(removals, rows);
		List<Answer<Relation>> fresh = except(rows, cancelled);
		return new WriteBuffer(base, insertions.asserting(fresh), removals.retracting(cancelled),
				stagedAssertions.appendAll(fresh), except(stagedRetractions, cancelled));
	}

	public WriteBuffer retracting(List<Answer<Relation>> rows) {
		List<Answer<Relation>> cancelled = staged(insertions, rows);
		List<Answer<Relation>> fresh = except(rows, cancelled);
		return new WriteBuffer(base, insertions.retracting(cancelled), removals.asserting(fresh),
				except(stagedAssertions, cancelled), stagedRetractions.appendAll(fresh));
	}

	/** The rows the opposite side already holds: the writes that cancel instead of landing. */
	private static List<Answer<Relation>> staged(AnswerStore opposite, List<Answer<Relation>> rows) {
		return rows.stream()
				.filter(row -> opposite.answers(Call.of(row.getRelation(), row.getReified())).iterator().hasNext())
				.collect(Collectors.toList());
	}

	private static List<Answer<Relation>> except(List<Answer<Relation>> rows, List<Answer<Relation>> cancelled) {
		return rows.stream().filter(row -> !cancelled.contains(row)).collect(Collectors.toList());
	}

	private static Array<Answer<Relation>> except(Array<Answer<Relation>> rows, List<Answer<Relation>> cancelled) {
		return rows.filter(row -> !cancelled.contains(row));
	}

	/** The rows this value's lineage staged to land, in staging order. */
	public List<Answer<Relation>> stagedAssertions() {
		return stagedAssertions.toJavaList();
	}

	/** The rows this value's lineage staged to remove, in staging order. */
	public List<Answer<Relation>> stagedRetractions() {
		return stagedRetractions.toJavaList();
	}

	@Override
	public Iterable<Answer<Relation>> answers(Call<Relation> probe) {
		return overlay(probe, base.answers(probe));
	}

	/**
	 * The signed delta over an already-fetched base — for callers that
	 * read the base themselves (a pinned read whose data must be the rows
	 * its pin certifies). A staged retraction hides its base row by image;
	 * a staged row that SUBSUMES a base row shadows it out of the
	 * delivery; same-key duplicates still ⊕-fold in the cell below.
	 */
	public Iterable<Answer<Relation>> overlay(Call<Relation> probe, Iterable<Answer<Relation>> baseAnswers) {
		List<Answer<Relation>> staged = StreamSupport.stream(insertions.answers(probe).spliterator(), false)
				.collect(Collectors.toList());
		Set<Reified<?>> removed = StreamSupport.stream(removals.answers(probe).spliterator(), false)
				.map(Answer::getReified)
				.collect(Collectors.toCollection(HashSet::new));
		JoinMap<Reified<?>, Condition> folded = Stream.concat(
						StreamSupport.stream(baseAnswers.spliterator(), false)
								.filter(base -> !removed.contains(base.getReified()))
								.filter(base -> staged.stream()
										.noneMatch(wide -> Answers.subsumes(wide, base))),
						staged.stream())
				.reduce(JoinMap.empty(Condition.RING),
						(map, answer) -> map.append(answer.getReified(), answer.getCondition()).getOrElse(map),
						Exceptions.throwingBiOp(UnsupportedOperationException::new));
		return IntStream.range(0, folded.size())
				.mapToObj(folded::get)
				.map(entry -> Answer.of(probe.getRelation(), entry._1, entry._2))
				.collect(Collectors.toList());
	}

	/** An upper bound: retractions never lower it — over-estimation is sound. */
	@Override
	public long estimate(Call<Relation> probe) {
		return base.estimate(probe) + insertions.estimate(probe);
	}

	/** The base names the world; the delta is this value's private view of it. */
	@Override
	public String id() {
		return base.id();
	}
}
