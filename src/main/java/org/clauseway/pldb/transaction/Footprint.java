package org.clauseway.pldb.transaction;

// ABOUTME: The composite pin: regions each with the pin captured at first touch —
// ABOUTME: a Pin itself, composed by union, leaves compared but never interpreted.

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.clauseway.logic.solving.Call;
import org.clauseway.pldb.relations.Relation;

/**
 * The composite {@link Pin}: every probed region paired with the pin
 * its source minted — the name of the WORLD-VECTOR a body of work
 * read. A base read's pin is a leaf; a derived producer's pin is the
 * {@link #union} of its parts' footprints; the transaction's footprint
 * is the same type at the top. The carrier never interprets leaves —
 * {@link #union} only compares them per region, and overlapping
 * regions carrying UNEQUAL pins refuse with {@link Transaction.Conflict}:
 * composing reads of two different worlds IS the conflict the commit
 * door speaks, met at composition instead of commit.
 * An empty footprint certifies vacuously: a decision that stood on no
 * reads cannot have stood on stale ones.
 */
public final class Footprint implements Pin {

	private final Map<Call<Relation>, Pin> pins;

	private Footprint(Map<Call<Relation>, Pin> pins) {
		this.pins = pins;
	}

	public static Footprint of(Map<Call<Relation>, Pin> pins) {
		return new Footprint(Collections.unmodifiableMap(new LinkedHashMap<>(pins)));
	}

	/** One region's read as a footprint — the leaf lifted to the composite. */
	public static Footprint of(Call<Relation> region, Pin pin) {
		return new Footprint(Collections.singletonMap(region, pin));
	}

	public static Footprint empty() {
		return new Footprint(Collections.emptyMap());
	}

	/**
	 * The composition: both sides' regions, and a region present in both
	 * must carry EQUAL pins — the same world read twice — or the union
	 * refuses: the parts read different worlds, and no sound answer can
	 * stand on both. Same-source footprints only: region keys are
	 * relation-identified and carry no source, so cross-source
	 * composition needs source-qualified keys — a named door, not this
	 * method.
	 */
	public Footprint union(Footprint other) throws Transaction.Conflict {
		Map<Call<Relation>, Pin> merged = new LinkedHashMap<>(pins);
		for (Map.Entry<Call<Relation>, Pin> entry : other.pins.entrySet()) {
			Pin resident = merged.putIfAbsent(entry.getKey(), entry.getValue());
			if (resident != null && !Objects.equals(resident, entry.getValue())) {
				throw new Transaction.Conflict("cross-world composition: region "
						+ entry.getKey().getRelation().getName()
						+ " was read at two different pins — the parts saw different worlds");
			}
		}
		return new Footprint(Collections.unmodifiableMap(merged));
	}

	/** Each probed region with the pin captured at its first touch. */
	public Map<Call<Relation>, Pin> pins() {
		return pins;
	}

	@Override
	public boolean equals(Object other) {
		return other instanceof Footprint && pins.equals(((Footprint) other).pins);
	}

	@Override
	public int hashCode() {
		return pins.hashCode();
	}

	@Override
	public String toString() {
		return pins.keySet().toString();
	}
}
