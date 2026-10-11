# pldb

The data boundary of the [`logic`](../logic) engine: relations as plain
Java functions over real backends.

## Why

If your facts live in a database, writing *logic* over them usually
means picking a poison:

- **Export and run Datalog** — the engine wants its facts resident, so
  you page the database through the network to discard most of it, and
  the database's indexes and statistics count for nothing.
- **Contort into SQL** — joins are great, recursive CTEs exist, but
  negation through rules, running a relation backwards, symbolic
  answers, and constraint pruning don't; every rule becomes a view you
  now maintain.
- **Accept an ORM** — objects and lazy loading; the logic lives nowhere.

pldb refuses the choice. The engine keeps its full semantics —
unification, tabling with completion detection, negation over completed
extensions, constraint propagation — and the boundary **translates**:

- A probe is a **region**: the bound columns *plus the engine's
  constraint knowledge about the free ones*. Regions compile to WHERE
  clauses, so the database ships only answers the solve can use.
- Shipped regions are **remembered**: a coverage ledger proves when a
  new probe is contained in an already-fetched one, and answers it from
  the local pool — no second round trip, and its cost estimate becomes
  exact, which the engine's query planner feeds on.
- The write face **closes the loop**: a commit must prove that the
  reads which justified it still hold. The double-lend anomaly that
  slips through append-only writes everywhere (write skew — no answers
  collide, so nothing detects the crossing) is refused here, by
  construction, on every backend.

And one decision above all: **a relation is a method.** The schema is
stated once — the signature is the arity, the builder the columns, the
last argument the backing — so the same domain code runs against
in-memory values in tests and PostgreSQL in anger, and cannot tell the
difference. [`apps/library`](../apps/library) is that claim as a test
suite.

## Getting it

Java 8, on Maven Central. `logic` — and under it `functional` and the
relocated vavr — comes transitively; `slf4j-api` is the only other runtime
dependency, and the JDBC driver is the application's own:

```xml
<dependency>
    <groupId>org.clauseway</groupId>
    <artifactId>pldb</artifactId>
    <version>0.1.0</version>
</dependency>
```

## The surface

Declare once:

```java
static Literal person(AnswerSource db, Unifiable<Integer> id, Unifiable<String> name) {
    return Literal.relation(Schema.class, "person")   // the class namespaces the
            .arg("id", id).indexed()                  // relation; the table name
            .arg("name", name)                        // stays the bare string
            .from(db);
}
```

Query in any direction — bound, free, or half of each:

```java
Unifiable<String> name = lvar();
person(db, lval(2), name).solve(name);      // who is #2?
person(db, lvar(), lval("Ada")).solve(...); // which ids are Ada?
```

Facts are the same functions, ground — stated to the write face, which
converts at the threshold:

```java
AnswerStore db = AnswerStore.empty().asserting(
        person(null, lval(1), lval("Ada")),
        person(null, lval(2), lval("Alan"))).get();
```

Rules are methods calling methods; recursion is the method calling
itself, and tabling makes it terminate — memoized in the solve's own
table, so the second entry into a region consumes the first instead of
re-deriving it:

```java
static Literal reachable(AnswerSource db, Unifiable<Integer> src, Unifiable<Integer> dst) {
    return Literal.relation(Rules.class, "reachable")
            .arg("src", src).arg("dst", dst)
            .solving(edge(db, src, dst)
                    .or(defer(() -> {
                        Unifiable<Integer> mid = lvar();
                        return edge(db, src, mid).and(reachable(db, mid, dst));
                    })));
}
```

Bare in a conjunction a literal READS; under `exclude()` it IMPOSES —
negation over the relation's *completed extension*, no modes, no cut,
and it composes with rules:

```java
// a copy with no active loan: negation through a derived relation
static Literal availableCopy(AnswerSource db, Unifiable<Integer> copyId) {
    return Literal.relation(Rules.class, "availableCopy")
            .arg("copyId", copyId)
            .solving(copy(db, copyId, lvar())
                    .and(exclude(onLoan(db, copyId))));
}
```

Even `p :- ¬p` is admitted and refused *loudly* — an unstratified
program becomes a cyclic wait the engine names, not a hang.

## The SQL tier

Schema by convention — relation name = table, property names = columns,
values JDBC-representable, columns non-null. No mapping layer, no
annotations:

```java
try (CachingSqlFetch source = CachingSqlFetch.pinned("app", connection)) {
    // the same person() method, now backed by a real table
    person(source, lvar(), name).solve(name);
}
```

What travels to the database is the *region*, not just the pattern.
Constraint knowledge compiles into the WHERE through a per-family
compiler registry (finite domains and nogoods out of the box, your
families pluggable):

```java
Unifiable<Integer> id = lvar();
FiniteDomain.dom(id, EnumeratedDomain.range(2, 5))
        .and(person(source, id, name))
        .solve(name);
// one round trip: SELECT name FROM person WHERE id IN (2, 3, 4)
// — the engine's pruning ran INSIDE the database; comparisons (leq,
// lss) push as <=/<, and nogoods as NOT(...) through the same registry
```

And what lands, stays:

```java
person(source, lvar(), lvar()).solve(...);   // fetches the relation, ONE SELECT
person(source, lval(2), lvar()).solve(...);  // no SQL at all: the ledger proves
                                             // this probe is covered; answered
                                             // from the pool, priced exactly
```

The connection is pinned at construction — a REPEATABLE READ (or
stronger) snapshot, so every read in a solve sees one consistent world.

## The write face

Reading never needs more than a source. *Writing* needs a
`Transaction`: a read face that records every probe, a write face that
stages facts, and a `commit()` that proves the recorded reads unmoved
before anything lands.

```java
try (Transaction tx = Transaction.over(
        Watermark.over(CachingSqlFetch.pinned("app", connection), pool::get))) {

    // validate against the snapshot — every probe is recorded
    boolean free = copy(tx, lval(1), lvar())
            .and(exclude(onLoan(tx, lval(1))))
            .solve(lvar()).findAny().isPresent();

    if (free) {
        Try<Nothing> landed = tx
                .asserting(loan(null, lval(500), lval(1), lval(100), lval(24)))
                .get()
                .commit();
        // Conflict inside the Try = a concurrent commit moved a region
        // this transaction read: reopen, re-solve — and the anomaly
        // reappears as an ordinary domain answer ("copy not available")
    }
}
```

Writes have both polarities: `asserting` lands facts, `retracting`
removes them BY FACT — certified like any write, so a reader pinned on
the region refuses instead of silently missing rows. Compaction is the
composition, not a mechanism: the domain names its spent clusters as a
rule (a loan WITH its return event), a question selects them, the door
removes them — and every *live* query answers exactly as before, which
is the receipt that makes it administration rather than a data change
([`apps/library`](../apps/library)'s `Maintenance`).

And a decision made in one request can be certified in a later one: a
read face hands out its `footprint()` with the answers, and a writing
transaction `requiring(premise)` proves those regions unmoved beside
its own reads at commit — the stateless-endpoint contract (GET returns
pinned answers, POST carries the premise, 409 means re-read) shipped
function-level as the library's `LibraryServer`.

## The question door

A query is a goal plus result-row templates — the schema functions
again, naming which answer values land in which columns:

```java
Unifiable<Integer> id = lvar();
Unifiable<Integer> copy = lvar();
Fiber<List<Answer>> closed = Question.select(
        loan(tx, id, copy, lvar(), lvar()).and(returned(tx, id)),
        loan(null, id, copy, lvar(), lvar()),   // the templates: one row
        returned(null, id));                    // per schema, per answer
List<Answer> rows = new BreadthFirstScheduler<>(closed).get();
```

The result is a `Fiber` — the caller constructs the engine, breadth-
first or ForkJoin or a chaos seed. Rows arrive with their derivation's
`Condition`: an answer proven under an undischarged guard is delivered
CAVEATED, not dropped — the write doors refuse it until the caller
decides (`unconditional()` is the explicit strengthening). Feeding a
selection to `retracting` is the whole DELETE..SELECT story with the
engine as the SELECT.

The proof is a capability the source declares **by type**, and which
proof a transaction stands on is the client's call: `Transaction.over`
leans on the backend's own, `Transaction.recording` pins every read and
carries the footprint. A source offering both opens either way; a
source with neither kind has no door to call, so "committing against an
uncertified source" is not a runtime error but a compile-time absence:

- **Native serialization** — the backend validates read sets itself
  (an *honest* SERIALIZABLE: PostgreSQL's SSI, two-phase-locking
  implementations). `SerializableSource.postgres(id, connection)`.
  Rented: zero schema demands; the price is trust — a backend whose
  SERIALIZABLE is snapshot isolation in costume (Oracle) must not wear
  the interface, and no code can check that for you.
- **Simulated serialization** — serialization reproduced above the
  backend: a `Pin` names the snapshot, commit proves the recorded
  `Footprint` unmoved under a commit lock, in one short transaction of
  its own. `Watermark` does it on any SQL backend with a working row
  lock (per-relation marks in one private table — a book commit never
  bounces a person writer); `SharedDatabase` does it in pure memory,
  where the persistent value *is* the snapshot:

```java
SharedDatabase store = SharedDatabase.empty();          // one history, in memory
Transaction a = Transaction.recording(store.open("a"));
Transaction b = Transaction.recording(store.open("b"));
// both validate, both stage, first commit wins, second meets Conflict —
// the whole concurrency story, testable in milliseconds, no container
```

Why this matters: two transactions each check "copy 1 is free", each
append a loan — no row collides, so write-write detection sees nothing,
and an append-only system quietly lends one book twice. Certifying
*reads* is the only cure, and both serializations are exactly that.

## What it is not

No ORM, no migrations, no connection pooling, no entity lifecycle —
facts are values, answers are facts, and the transaction is the only
session there is. Pre-1.0 like its siblings: 0.1.0 is on Maven Central,
the shape is settled, the API carries no compatibility guarantee yet —
and honesty over polish: the interesting failure modes are receipted as
tests, not documented around.

## Where knowledge lives

- `docs/design/domain-layer.md` — the north star: the engine at the
  data boundary, and what the algebra does and does NOT buy.
- `docs/notes/table-as-the-source.md` — the read seam as built:
  tabling compresses, the solve owns the residence, pldb translates.
- `docs/notes/transaction.md` — the write face as built: the two
  serializations, the protocol, the granularity ladder.
- `docs/notes/pins-stay-off-engine.md` — the pin ruling: the read seam
  and the transaction map are the whole mechanism; nothing rides the
  engine.
- `docs/notes/wire-face.md` — the write door over HTTP: premise
  header, stateless verdict, the opaque-token ruling.
- `docs/notes/persist.md` — a domain write as a partially-bound solve;
  `docs/notes/namespaced-relations.md` — why the class is the
  namespace and the table name stays bare.
- `docs/design/` — the SQL tier's design line (table constraints,
  query planning, ergonomics).
- Vocabulary is gated by [`logic/docs/glossary.md`](../logic/docs/glossary.md)
  — one gate for all three repos.
