package org.clauseway.pldb.transaction;

// ABOUTME: Native serialization: the backend validates read sets at commit
// ABOUTME: itself; the source owns the whole commit door and its dialect.

import org.clauseway.pldb.relations.Relation;
import java.util.List;
import org.clauseway.pldb.AnswerSource;
import org.clauseway.logic.solving.Answer;

/**
 * A source whose backend serializes transactions itself — an
 * isolation that actually validates read sets (PostgreSQL's SSI, the
 * two-phase-locking implementations). {@link #commit} lands the flush
 * and commits the source's own snapshot transaction; a refusal in the
 * backend's conflict dialect answers {@code false} (the world moved —
 * re-solve), any other failure throws. No pin and no footprint: the
 * backend tracked the reads itself. The implementor is the adapter
 * that KNOWS its backend keeps that promise; a backend whose
 * SERIALIZABLE is snapshot isolation in costume must not wear this
 * interface.
 */
public interface NativeSerialization extends AnswerSource, AutoCloseable {

	boolean commit(List<Answer<Relation>> asserted, List<Answer<Relation>> retracted);
}
