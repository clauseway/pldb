package org.clauseway.pldb.sql.compiler;

// ABOUTME: Direct receipts for the nogood compiler: De Morgan over the registry —
// ABOUTME: literals negate via their families, disjunctions push whole or not at all.

import static org.assertj.core.api.Assertions.assertThat;
import static org.clauseway.logic.finitedomain.FiniteDomain.dom;
import static org.clauseway.logic.nogoods.Exclusion.exclude;
import static org.clauseway.logic.unification.terms.LVal.lval;
import static org.clauseway.logic.unification.terms.LVar.lvar;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.clauseway.logic.constraints.Posting;
import org.clauseway.logic.constraints.Propagation;
import org.clauseway.logic.constraints.store.Atom;
import org.clauseway.logic.constraints.store.Renaming;
import org.clauseway.logic.finitedomain.FiniteDomainConstraints;
import org.clauseway.logic.finitedomain.Longs;
import org.clauseway.logic.nogoods.Nogood;
import org.clauseway.logic.nogoods.NogoodConstraints;
import org.clauseway.logic.unification.Prefix;
import org.clauseway.logic.unification.Substitutions;
import org.clauseway.logic.unification.terms.Any;
import org.clauseway.logic.unification.terms.LVar;
import org.clauseway.logic.unification.terms.Name;
import org.clauseway.logic.unification.terms.Term;
import org.clauseway.logic.unification.terms.Unifiable;
import org.clauseway.pldb.constraints.TableConstraints;
import org.clauseway.pldb.inmemory.AnswerStore;
import org.clauseway.pldb.relations.Literal;
import org.clauseway.pldb.relations.Schema;
import org.clauseway.pldb.relations.Property;
import org.clauseway.pldb.relations.Relation;
import org.clauseway.pldb.sql.SqlCompiler;
import org.junit.Test;

public class NogoodSqlCompilerTest {

	private final Unifiable<Long> x = lvar();
	private final Unifiable<String> y = lvar();

	private final Map<Class<?>, SqlCompiler> families = new HashMap<>();

	private NogoodSqlCompiler compiler() {
		families.put(FiniteDomainConstraints.class, new FiniteDomainSqlCompiler());
		NogoodSqlCompiler nogoods = new NogoodSqlCompiler(families);
		families.put(NogoodConstraints.class, nogoods);
		return nogoods;
	}

	private Optional<SqlPredicate> compiled(Posting exclusion) {
		Atom<?> atom = ((Posting.Activation) exclusion).getItem();
		return compiler().compile(atom, this::column);
	}

	private Optional<String> column(Term<?> term) {
		return term.equals(x) ? Optional.of("id")
				: term.equals(y) ? Optional.of("name")
				: Optional.empty();
	}

	@Test
	public void aBindingLiteralNegatesDirectly() {
		Optional<SqlPredicate> p = compiled(exclude(x.unifies(3L)));
		assertThat(p).isPresent();
		assertThat(p.get().getFragment()).isEqualTo("id <> ?");
		assertThat(p.get().getParameters().toJavaList()).containsExactly(3L);
		assertThat(p.get().isExact()).isTrue();
	}

	@Test
	public void aStoreLiteralNegatesThroughItsFamily() {
		Optional<SqlPredicate> p = compiled(exclude(dom(x, Longs.range(1, 3))));
		assertThat(p).isPresent();
		assertThat(p.get().getFragment()).isEqualTo("NOT (id IN (?, ?))");
		assertThat(p.get().getParameters().toJavaList()).containsExactly(1L, 2L);
	}

	@Test
	public void aMultiLiteralConjunctDisjoinsItsNegations() {
		Optional<SqlPredicate> p = compiled(exclude(Posting.all(
				x.unifies(2L),
				y.unifies("Alan"))));
		assertThat(p).isPresent();
		assertThat(p.get().getFragment()).isEqualTo("(id <> ? OR name <> ?)");
		assertThat(p.get().getParameters().toJavaList()).containsExactly(2L, "Alan");
	}

	@Test
	public void oneUncompilableLiteralRefusesTheWholeConjunct() {
		// a literal of a family with no compiler (the posted table): pushing
		// the REST of the disjunction would strengthen — whole or not at all
		Property<Long> id = Property.of("id");
		Relation r = Literal.relation(NogoodSqlCompilerTest.class, "r").args(Schema.builder().arg("id", lvar()).build()).from(null).getRel();
		Posting posted = TableConstraints.posted(AnswerStore.empty(), r, Collections.singletonList(x));
		assertThat(compiled(exclude(Posting.all(x.unifies(2L), posted)))).isEmpty();
	}

	@Test
	public void aNestedConjunctionArrivesFlatFromTheEnvelope() {
		// the Nogood envelope flattens nested alls at construction — the
		// compiler receives one flat conjunction and disjoins it whole
		Optional<SqlPredicate> p = compiled(exclude(Posting.all(
				x.unifies(2L),
				Posting.all(y.unifies("Alan"), x.unifies(5L)))));
		assertThat(p).isPresent();
		assertThat(p.get().getFragment()).isEqualTo("(id <> ? OR name <> ? OR id <> ?)");
	}

	@Test
	public void aCrossedResolutionCompilesAsItsUnifications() {
		// a prefix literal crosses as the conjunction of its binds (the
		// Renamer's row), so the region hands this compiler UnifyGoals —
		// receipted through the same rename the extraction performs
		Posting exclusion = exclude(Propagation.resolve(
				Prefix.binding(Substitutions.empty(), (LVar<?>) x.asVar().get(), lval(3L)).get()));
		Atom<?> atom = ((Posting.Activation) exclusion).getItem();
		Atom<?> crossed = atom.rename(Renaming.of(
						Collections.<Name<?>, Term<?>> singletonMap(x.asVar().get(), Any.of(0))))
				.ground();
		Optional<SqlPredicate> p = compiler().compile(crossed,
				term -> term.equals(Any.of(0)) ? Optional.of("id") : Optional.empty());
		assertThat(p).isPresent();
		assertThat(p.get().getFragment()).isEqualTo("id <> ?");
		assertThat(p.get().getParameters().toJavaList()).containsExactly(3L);
	}

	@Test
	public void aFusedAtomConjoinsItsConjunctsAndMayDropSome() {
		// two same-surface exclusions fuse into one atom: the conjunction may
		// push a SUBSET (dropping a conjunct weakens — the lawful direction),
		// marked weakened when it does
		Nogood first = (Nogood) ((Posting.Activation) exclude(x.unifies(2L))).getItem();
		Nogood second = (Nogood) ((Posting.Activation) exclude(x.unifies(4L))).getItem();
		Optional<SqlPredicate> both = compiler().compile(first.combine(second), this::column);
		assertThat(both).isPresent();
		assertThat(both.get().getFragment()).isEqualTo("(id <> ? AND id <> ?)");
		assertThat(both.get().isExact()).isTrue();

		// two different drops, one law each: dropping a CONJUNCT of the fused
		// atom (¬c1 ∧ ¬c2 -> ¬c1) removes a conjunct from a conjunction —
		// weaker, admits more, sound. Dropping a LITERAL inside one conjunct
		// (¬(l1 ∧ l2) -> ¬l1) strengthens — that is the move
		// oneUncompilableLiteralRefusesTheWholeConjunct forbids. Negation
		// flips the direction ONCE, at the conjunct boundary.
		Property<Long> id = Property.of("id");
		Relation r = Literal.relation(NogoodSqlCompilerTest.class, "r").args(Schema.builder().arg("id", lvar()).build()).from(null).getRel();
		Posting posted = TableConstraints.posted(AnswerStore.empty(), r, Collections.singletonList(x));
		Nogood refused = (Nogood) ((Posting.Activation) exclude(posted)).getItem();
		Optional<SqlPredicate> partial = compiler().compile(first.combine(refused), this::column);
		assertThat(partial).isPresent();
		assertThat(partial.get().getFragment()).isEqualTo("id <> ?");
		assertThat(partial.get().isExact()).isFalse();
	}
}
