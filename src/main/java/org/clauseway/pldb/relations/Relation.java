package org.clauseway.pldb.relations;

import java.util.Arrays;
import java.util.Optional;
import java.util.stream.Collectors;
import org.clauseway.functional.tuples.Tuple;
import org.clauseway.functional.tuples.Tuple2;
import org.clauseway.vavr.collection.Stream;

/**
 * A named relation with a declared column list: {@link #getName()} is the
 * physical name a backend spells (the table, the endpoint), and
 * {@link #getArgs()} the {@link Property} columns in positional order.
 * {@link #getId()} renders the name with its column names, and
 * {@link #indexOf} locates a column by name. {@link RelationN} is the value
 * implementation; a relation is the token every {@code Answer<Relation>}
 * and {@code Call<Relation>} in pldb carries.
 */
public interface Relation {
	String getName();

	Property<?>[] getArgs();

	default String getId() {
		return String.format("%s(%s)", getName(),
				Arrays.stream(getArgs())
						.map(Property::getName)
						.collect(Collectors.joining(", ")));
	}

	default Optional<Integer> indexOf(Property<?> property) {
		return Stream.range(0, getArgs().length)
				.map(i -> Tuple.of(i, getArgs()[i]))
				.filter(t -> property.getName().equals(t._2.getName()))
				.headOption()
				.toJavaOptional()
				.map(Tuple2::_1);
	}

	static RelationN of(String name, Property<?>... args) {
		return RelationN.of(name, args);
	}
}
