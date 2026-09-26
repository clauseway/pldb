package org.clauseway.pldb.transaction;

// ABOUTME: Simulated serialization: a source whose reads return data WITH the pin
// ABOUTME: naming their world, and whose commit proves the footprint unmoved.

import java.util.List;
import org.clauseway.logic.solving.Call;
import org.clauseway.pldb.AnswerSource;
import org.clauseway.logic.solving.Answer;
import org.clauseway.pldb.relations.Relation;

/**
 * A source that serializes by proof, at the footprint's grain:
 * {@link #read} answers a probe and names the world the answers came
 * from — data and pin as ONE result. {@link #commit} runs ONE short
 * transaction of the source's own — take the commit lock, prove every
 * footprint region unmoved since ITS pin, land the facts, record the
 * movement, commit — or answer {@code false} with nothing landed. It
 * cannot run inside a read snapshot: proving "unmoved" requires
 * reading the CURRENT world, which a snapshot by definition refuses
 * to show.
 *
 * <p>THE CAPTURE ORDERING IS THE SOUNDNESS: within one {@code read},
 * the pin must be captured atomically with, or BEFORE, the answers it
 * rides with. Capture-before is sound — if commit finds the pin
 * unmoved, nothing landed between capture and proof, so the data read
 * after it belongs to the pin's world. Capture-after silently is not:
 * a commit landing between the data and the pin hands back stale
 * answers under a fresh pin, and the proof passes on reads the
 * decision never saw. A snapshot source satisfies the contract for
 * free; a live source must order its two statements; a wire source
 * (one response, body plus validator) has no window at all.
 *
 * <p>Granularity is the implementor's: a coarse source conflicts on
 * any movement, a per-relation source compares only the footprint's
 * relations, a region-aware source matches delta rows. Conservative
 * refusal is always sound.
 */
public interface SimulatedSerialization extends AnswerSource, AutoCloseable {

	Pinned<Iterable<Answer<Relation>>> read(Call<Relation> probe);

	boolean commit(Footprint read, List<Answer<Relation>> asserted, List<Answer<Relation>> retracted);

	@Override
	default Iterable<Answer<Relation>> answers(Call<Relation> probe) {
		return read(probe).getValue();
	}
}
