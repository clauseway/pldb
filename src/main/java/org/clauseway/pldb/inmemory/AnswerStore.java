package org.clauseway.pldb.inmemory;

// ABOUTME: The Call-native membership store: Answer rows keyed by reified image,
// ABOUTME: conditions ⊕-fold; completeness is coverage's claim, worlds are pins'.

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.Value;
import org.clauseway.logic.solving.Call;
import org.clauseway.logic.unification.terms.Reified;
import org.clauseway.pldb.AnswerSource;
import org.clauseway.pldb.Writer;
import org.clauseway.pldb.relations.Answer;
import org.clauseway.pldb.relations.Answers;
import org.clauseway.pldb.relations.Relation;
import org.clauseway.vavr.collection.LinkedHashMap;
import org.clauseway.vavr.collection.Map;

/**
 * The in-memory store, native to the seam's vocabulary. Its semantics
 * is MEMBERSHIP, and only membership: a row is the claim "this image
 * belongs to this relation, under this condition" — monotone and
 * direction-free, so answers join their relation's rows wherever the
 * probe that fetched them was narrow or wide, a wide row is the
 * ∀-schema claim over its frees, and a duplicate image ⊕-folds its
 * conditions (membership holds if EITHER derivation's guard does;
 * image equality is alpha-equivalence). The two claims the store
 * deliberately cannot speak live beside it: "these are ALL the rows
 * matching a probe" is COVERAGE's, and "this extension, as of this
 * world" is the PIN's — writes here never assert either.
 *
 * <p>The RELATION's declared access pattern is the index spec: columns
 * flagged {@code indexed()} keep per-value buckets in Term vocabulary
 * (null is a key; a row free at an indexed column lives in the
 * wildcard set, matching every probe); {@link #answers} intersects the
 * ground indexed positions' buckets and filters the remaining bound
 * positions. An unflagged relation full-scans, correctly. Residues
 * are ignored under the standing license — a source may only
 * over-deliver, and here over-delivery costs a walk.
 * Couplings between frees are likewise over-delivered; the consumer's
 * restate filters. The value is PERSISTENT: every insert mints a new
 * store, ancestors keep answering as before — which is why the
 * inherited object-identity {@link #id()} is right: each value IS its
 * data (same object, same extension — the sound direction), and a
 * store playing the shared-backend role gets its declared name from
 * the HANDLE that shares it, never from the value.
 */
@Value
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public class AnswerStore implements AnswerSource, Writer<AnswerStore> {

	Map<Relation, AnswerIndex> relations;

	public static AnswerStore empty() {
		return new AnswerStore(LinkedHashMap.empty());
	}

	public AnswerStore with(Relation relation, Answer answer) {
		AnswerIndex answers = relations.getOrElse(relation, AnswerIndex.empty());
		return new AnswerStore(
				relations.put(relation, answers.with(positions(relation), answer)));
	}

	/** The strict write face; {@link #with} stays the seam's permissive dock. */
	@Override
	public AnswerStore asserting(List<Answer> rows) {
		AnswerStore grown = this;
		for (Answer row : rows) {
			grown = grown.with(row.getRelation(), Answers.landable(row));
		}
		return grown;
	}

	public AnswerStore withAll(Relation relation, Iterable<Answer> answers) {
		AnswerStore grown = this;
		for (Answer answer : answers) {
			grown = grown.with(relation, answer);
		}
		return grown;
	}

	/**
	 * The removal door: the fact's membership claim leaves whole — its
	 * ⊕-folded condition with it; retracting the absent is a no-op.
	 */
	@Override
	public AnswerStore retracting(List<Answer> rows) {
		AnswerStore shrunk = this;
		for (Answer row : rows) {
			shrunk = shrunk.without(row.getRelation(),
					Answers.landable(row).getReified());
		}
		return shrunk;
	}

	public AnswerStore without(Relation relation, Reified<?> image) {
		return relations.get(relation)
				.map(answers -> new AnswerStore(
						relations.put(relation, answers.without(positions(relation), image))))
				.getOrElse(this);
	}

	@Override
	public Iterable<Answer> answers(Call<Relation> probe) {
		return relations.get(probe.getRelation())
				.map(answers -> answers.answers(probe.getRelation(), positions(probe.getRelation()), probe))
				.getOrElse(Collections.emptyList());
	}

	/** Upper bound from the narrowest consulted bucket — exact when unfiltered. */
	@Override
	public long estimate(Call<Relation> probe) {
		return relations.get(probe.getRelation())
				.map(answers -> answers.estimate(positions(probe.getRelation()), probe))
				.getOrElse(0L);
	}

	/** The relation's declared access pattern IS the index spec — plain scratch. */
	private static Set<Integer> positions(Relation relation) {
		return IntStream.range(0, relation.getArgs().length)
				.filter(i -> relation.getArgs()[i].isIndexed())
				.boxed()
				.collect(Collectors.toSet());
	}
}
