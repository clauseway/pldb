# Relational structs — a relation declared as its domain type

**STATUS: DESIGN SKETCH (July 2026, human-driven, corrected once — an earlier
draft over-built codegen "to name args," which the builder does for free; the
real justification is SCHEMA-AS-A-TYPE). CORRECTED AGAIN (October 2026,
`docs/notes/schema-is-a-mapper.md`, which is AS BUILT): the Row of §3 is
`Schema<D>`, a logic `Mapper` — the row's variables as keys, a `map` from
a `Solution` into the Java value — plus `columns()`; it is NOT a signed
structure, since the engine never rebuilds a row. §6's generator is that
note's open obligation (4): it would emit `keys()`, `columns()` and the
value constructor call from a plain-typed description, and the
hand-written schemas in `apps/cargo` are what it is measured against.
Companions:
`external-relations.md` (the backend contract and endpoint generation this
authors over), `database-ergonomics.md` (db-rides-the-Package).**

---

## 1. Two separate problems — don't conflate them

- **Query ergonomics** — the two existing options each give up half of what you
  want. Positional `exists(db, v0..vN)` is membership- and type-safe (its
  signature IS this relation's columns) but UNNAMED and all-args. A generic
  `bind(Property, val)` builder is named and partial but membership-UNSAFE (it
  accepts any `Property`, so a wrong column compiles). Getting all four — named,
  typed, membership-checked, partial — needs GENERATED per-column methods (§2).
- **Schema declaration** — a relation's columns, types, constraints, indexes are
  hand-authored today: `Property<T>` constants + a `relation(...)` call +
  `Constraint.foreignKey/unique` calls + a DTO for answers. Four artifacts, all
  by hand, all restating the same shape. Fixed by making the DOMAIN STRUCT the
  single source and generating the rest (§3).

So codegen earns its keep TWICE: a membership-safe named query API a hand-written
builder cannot express (§2), and schema-as-a-type, the JPA-entity pattern (§3).
`@Relational` DECLARES a relation and generates its query builder; the two layer.

## 2. The query layer — a generated per-column fluent builder

Membership-safety requires the `Property` to know its owning relation, which a
generic `bind` cannot enforce. The fix is a builder with a METHOD PER COLUMN,
generated onto the companion (the jOOQ / QueryDSL pattern):

```java
OfferRelation.query()
    .bidder(lval("bob"))         // generated method — EXISTS ONLY for Offer's columns
    .address(addr)               // .someOtherColumn(...) does not compile: no such method
    .solve(db)                   // unbound columns default to fresh holes
    .map(row -> row.address());  // Term<String>, named read-out

// answers straight to a domain object (the existing exists(Fact, fn) destructuring):
OfferRelation.query().bidder(lval("bob")).solveAs(db, Offer::new);   // Stream<Offer>
```

Named (method = column), typed (`.bidder(Unifiable<String>)`),
MEMBERSHIP-CHECKED (a wrong column is a compile error), partial (call a subset).
A generic runtime builder cannot do this — per-relation methods must be
generated — which is exactly why this is a codegen win, not a builder win.

Two lighter shapes, for the record:
- **Generic `bind(Property, val)`** — named and partial but membership-UNSAFE
  (accepts any `Property`). The no-codegen FALLBACK, usable before a relation is
  annotated; do not trust it for correctness.
- **Phantom-typed `Property<Offer, String>` + `bind(Property<Offer,?>, …)`** —
  membership-safe with ONE generic builder (`bind(ADDRESS, x)`), at the cost of
  owner-typed constants (codegen emits them) and no per-column autocomplete. The
  middle option if per-column methods feel heavy.

## 3. `@Relational` — the struct is the single source of truth

The win of codegen is not naming args — it is collapsing the four hand-authored
schema artifacts into ONE annotated struct, and generating the rest INCLUDING
the relation (so there is no separate relation declaration — the thing that made
an earlier draft ludicrous).

```java
@Value @Relational
public class Offer {
    @Indexed String address;          // -> Property<String> ADDRESS, indexed
    @Indexed String bidder;           // -> Property<String> BIDDER, indexed
    int offerPrice;                   // -> Property<Integer> OFFER_PRICE
    int offerDate;
    // @ForeignKey / @Unique annotations carry the constraints
}
```

generates the companion `OfferRelation`:

```java
// GENERATED
public final class OfferRelation {
    public static final Property<String>  ADDRESS = Property.of("address"); /* ... */
    public static final class Row { /* Unifiable fields named after struct fields; .address(), ... */ }
    public static Query query() { /* the §2 per-column fluent builder — .address()/.bidder()/... */ }
    public static final Relation RELATION = /* columns + constraints + indexes + fact-lookup body */;
}
```

You authored ONE thing — the domain struct with field annotations. The
`Property` constants (named after fields), the Row, the constraints, the
indexes, and the `Relation` all fall out of it. That same struct is
simultaneously the schema, the query builder's column source, the answer type,
and — untouched, since it is a plain `@Value` POJO — the jackson wire type at
the REST edge. One declaration, no restatement.

## 4. Base vs derived — schema-vs-body, one annotation

`@Relational` spans both kinds of relation; they differ by what the struct
CARRIES:

```java
@Value @Relational                    // BASE: schema annotations, NO body -> query is fact lookup
public class Offer { @Indexed String address; String bidder; int offerPrice; int offerDate; }

@Value @Relational                    // DERIVED (a view): a body, NO constraints -> query is the clauses
public class CurrentOffer {
    String address; String bidder; int offerPrice; int offerDate;
    static Goal rule(Row r) {          // the view definition, over the generated Row
        Unifiable<Integer> d = lvar();
        return OfferRelation.query().address(r.address()).bidder(r.bidder()).solve()
            .and(Aggregate.max(d, OfferRelation.query()
                    .address(r.address()).bidder(r.bidder()).solve(), r.offerDate()));
    }
}
```

Both generate a `Relation`. A base relation's query is a fact lookup; a derived
relation's query runs `rule`. A derived relation has no constraints/indexes to
declare (it is computed, not stored) — it declares a body instead. To the
server, `OfferRelation.RELATION` and `CurrentOfferRelation.RELATION` are the same
type (the uniform `RegisteredRelation` — `query(Row) -> Goal` plus columns,
modes, project, count; `external-relations.md`), and endpoint generation cannot
tell them apart. That indistinguishability is the point.

## 5. What one annotated struct becomes

| role | for a BASE relation | for a DERIVED relation |
|---|---|---|
| declaration | columns + constraints + indexes | columns + a `rule` body |
| the `Relation` | generated (fact lookup) | generated (runs `rule`) |
| query builder | generated per-column fluent (`.address()...`) | same |
| answer / domain type | the `@Value` itself | the `@Value` itself |
| jackson wire type | the `@Value`, untouched | the `@Value`, untouched |

The per-column query builder (§2) is generated from the same struct — so
`@Relational` declares AND generates the membership-safe query API; the two are
one artifact, not competitors.

## 6. Boundaries

- **Companion, not inner class** — a standard annotation processor generates new
  files, it cannot add an inner class to `Offer`; so `OfferRelation.Row`, not
  `Offer.Row`, exactly as JPA-metamodel/Immutables/AutoValue/MapStruct. Literal
  inner-class needs lombok-style AST hacking; declined.
- **Lombok ordering is the one flaky surface** — `@Relational` sees the struct's
  FIELDS but maybe not lombok's generated getters/ctor; generate code that
  references `Offer` through its public API (`Offer::new`, `getAddress()`), pin
  the lombok version, and test the compile end to end.
- **Derived modes are AUTHORED, not free** — a base relation supports all binding
  patterns; a derived relation supports only what its `rule` can run (a
  correlated aggregate needs its group bound; an FD-backward view needs a domain
  bound). Declared on the struct, gated by the server; a wrong declaration fails
  loud at the suspend-backstop (`external-relations.md`), never silent.
- **It is a real annotation processor** (`@AutoService(Processor)`, into
  `generated-sources`, IDE annotation-processing enabled) — the narrow,
  legitimate return of the deleted codegen, now paying for "declare a whole
  relation as its domain type," not for "name five args."

## 7. Build order

1. **The generic `bind` builder + `Relations.derived(name, cols).body(fn)`** — a
   runtime `query().bind(Property, value)` + `solveAs(ctor)`, and a factory
   giving a derived relation the same `Relation` face as a base one, so the
   server sees `List<Relation>` uniformly. No processor. Removes the
   positional-args pain and proves the uniform interface — but the `bind` builder
   is MEMBERSHIP-UNSAFE (§2), a known temporary hole.
2. **`@Relational` processor** (§3–4) — generate the companion from the annotated
   struct: Property constants, the per-column fluent builder (§2, which CLOSES
   the membership-safety hole from step 1), constraints/indexes, and `RELATION`;
   base = schema annotations, derived = a `rule`. Two payoffs at once —
   schema-as-single-source and the membership-safe named query API.
3. **Endpoint generation** — off the uniform `Relation`, consuming
   `external-relations.md`'s mode/endpoint machinery.

Step 1 is worth building on its own (unblocks querying today) but is a stopgap
on both counts — membership-unsafe queries, hand-declared schema. Step 2 is what
the processor is FOR, and it earns its keep TWICE: it collapses four
hand-authored schema artifacts into one AND generates a membership-safe named
query API that no runtime builder can express. Neither is "a nicer way to name
arguments."
