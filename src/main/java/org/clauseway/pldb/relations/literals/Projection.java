package org.clauseway.pldb.relations.literals;

// ABOUTME: The projection a literal's builder applies: the projected() markers among a row's members, claimed
// ABOUTME: by the first head they appear in, and the head that remains once those columns drop out of it.

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.clauseway.logic.unification.MiniKanren;
import org.clauseway.logic.unification.structures.LOption;
import org.clauseway.logic.unification.terms.Unifiable;
import org.clauseway.pldb.relations.Projected;
import org.clauseway.pldb.relations.Property;
import org.clauseway.pldb.relations.Relation;
import org.clauseway.pldb.relations.RelationN;
import org.clauseway.vavr.collection.Array;

/**
 * A {@link Projected} column drops out of the head into a GENERATED
 * projection rule over the full literal — the tabled cell folds
 * duplicates (∃-projection is set semantics), the posted form makes
 * {@code exclude} an honest ¬∃, and inside the body the marker is an
 * ordinary fresh variable. A projected column marked ground() refuses:
 * an input cannot be projected away.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
final class Projection {

	/** The head a projection keeps: relation and arguments without the projected columns. */
	static final class Kept {
		final Relation rel;
		final Array<Unifiable<?>> args;

		Kept(Relation rel, Array<Unifiable<?>> args) {
			this.rel = rel;
			this.args = args;
		}
	}

	/** The projection marker at the arg, or one level inside a some(): present, don't care. */
	static Optional<Projected<?>> marker(Unifiable<?> value) {
		if (value instanceof Projected) {
			return Optional.of((Projected<?>) value);
		}
		return value.asVal()
				.flatMap(MiniKanren::asLOption)
				.filter(option -> !option.isNone())
				.map(LOption::getValue)
				.filter(Projected.class::isInstance)
				.map(inner -> (Projected<?>) inner);
	}

	/** The head without the projected columns; empty when nothing is projected and the full literal is the literal. */
	static Optional<Kept> kept(String namespace, String name, List<Property<?>> columns, List<Unifiable<?>> values,
			List<Boolean> projectedHere) {
		List<Property<?>> keptColumns = new ArrayList<>();
		List<Unifiable<?>> keptValues = new ArrayList<>();
		StringBuilder keptNames = new StringBuilder();
		for (int i = 0; i < values.size(); i++) {
			if (projectedHere.get(i)) {
				if (columns.get(i).isGround()) {
					throw new IllegalStateException("relation '" + name + "': column '"
							+ columns.get(i).getName() + "' is ground — an input cannot be projected away");
				}
				continue;
			}
			keptColumns.add(columns.get(i));
			keptValues.add(values.get(i));
			keptNames.append(keptNames.length() == 0 ? "" : ",").append(columns.get(i).getName());
		}
		if (keptColumns.size() == values.size()) {
			return Optional.empty();
		}
		return Optional.of(new Kept(
				RelationN.of(namespace, name + "[" + keptNames + "]", keptColumns.toArray(new Property<?>[0])),
				Array.ofAll(keptValues)));
	}
}
