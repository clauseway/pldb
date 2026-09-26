package org.clauseway.pldb.relations;

import org.clauseway.logic.solving.Answer;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Value;
import org.clauseway.logic.constraints.Posting;
import org.clauseway.logic.unification.terms.Unifiable;
import org.clauseway.pldb.AnswerProducer;
import org.clauseway.pldb.AnswerSource;
import org.clauseway.pldb.constraints.TableConstraints;

@Value
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class RelationN implements Relation {
	/** IDENTITY only — the physical name ({@link #getName}) never carries it. */
	String namespace;
	String name;
	Property<?>[] args;

	public static RelationN of(String name, Property<?>... args) {
		return of("", name, args);
	}

	public static RelationN of(String namespace, String name, Property<?>... args) {
		validateProperties(args);
		return new RelationN(namespace, name, args);
	}

	public static void validateProperties(Property<?>... args) {
		List<String> duplicates = Arrays.stream(args)
				.collect(Collectors.groupingBy(Property::getName))
				.entrySet()
				.stream()
				.filter(e -> e.getValue().size() > 1)
				.map(Map.Entry::getKey)
				.collect(Collectors.toList());
		if (!duplicates.isEmpty()) {
			throw new IllegalArgumentException("Duplicated property names: " + duplicates);
		}
	}

	@Override
	public Property<?>[] getArgs() {
		return args;
	}

	public Literal apply(AnswerSource source, Unifiable<?>... args) {
		return relation(source, this, args);
	}

	public static Literal relation(AnswerSource source, Relation rel, Unifiable<?>... args) {
		return Literal.of(source, rel, Arrays.asList(args));
	}

	public static Literal relation(AnswerProducer producer, Relation rel, Unifiable<?>... args) {
		return Literal.of(producer, rel, Arrays.asList(args));
	}

	public Posting posted(AnswerSource source, Unifiable<?>... args) {
		return posted(source, this, args);
	}

	public static Posting posted(AnswerSource source, Relation rel, Unifiable<?>... args) {
		return TableConstraints.posted(source, rel, Arrays.asList(args));
	}

	public Posting posted(AnswerProducer producer, Unifiable<?>... args) {
		return posted(producer, this, args);
	}

	public static Posting posted(AnswerProducer producer, Relation rel, Unifiable<?>... args) {
		return TableConstraints.posted(producer, rel, Arrays.asList(args));
	}

	public Answer<Relation> apply(Object... vs) {
		return fact(vs);
	}

	/** The relation's stored-row face: ground values in declared order. */
	public Answer<Relation> fact(Object... vs) {
		return Answers.answer(this, Arrays.asList(vs));
	}
}