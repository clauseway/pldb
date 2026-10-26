# A schema is a selector with column names: variables in, a Java value out, and the engine never rebuilds a row

- **status**: receipted (October 2026; the human's direction after three
  tries in two days — see the lineage at the end)
- **evidence held**: demonstration — logic `SelectorTest` (4), pldb
  `SchemaTest` (10), and the cargo port's five row types
  (`Delivery`, `Leg`, `RouteSpecification`, `HandlingActivity`,
  `HandlingEvent`) with their reads through `Query.select(row)`;
  negative witness — the two designs this replaces, each green and each
  wrong for a stated reason (lineage).
- **imports**: Selector, Solution, Selection, Schema, External, Literal,
  Term / Unifiable / Reified, structural contract, a don't-care column
  is `projected()` (glossary; all ratified).
- **obligations**:
  - (1) MET. `Selector<D>` in logic's `solving`: `keys()`, `names()`
    (what a solution shows the keys by; a Schema answers its columns) and
    `map(Solution)`; `Query.select(selector)` → `Selection<D>`, each
    solution through `decided()` before `map`, whose refusal renders the
    solution whole, `(name: cell, …) : residuals`. The solutions reading is
    the identity mapping; `stream()` is the one terminal.
  - (2) MET. `Schema<D> extends Selector<D>` adds `columns()`, one
    `Property` per key in order; a typed row states both in one
    `describe()` chain and `keys()`/`columns()` are read off it; the
    literal builder takes the row (`relation(ns, name).schema(row)`), or
    names an anonymous row's columns on the literal (`.arg(name, x)…`);
    `Schema.Anonymous` is a `Schema<Solution>` that describes itself. Not a `Structural`; no `withMembers`, no default
    `probe()` — a probe is a constructor call with `projected()` per
    column.
  - (3) MET. The port: fields `Unifiable<T>`, rule bodies unify the
    fields directly, `map(Solution)` reads each cell by its field,
    reads are `Query.of(row.stored(db)).select(row).stream()`. The two
    rows whose Java value drops the ordinal (`Leg`, `HandlingActivity`)
    map to `Tuple2<Integer, Dto>` so the reads can sort by it.
  - (4) OPEN. Generation: a description with plain types from which
    `keys()`, `columns()` and the DTO constructor call in `map` could be
    generated. Six schemas is not enough to pay for an annotation
    processor; the hand-written form is what it would be measured
    against. Trigger: a second application with more than a dozen
    row types.
  - (5) OPEN. The ordinal problem in (3) is solved by a tuple; a named
    value or a selector that carries its sort key would read better.
    Trigger: a third row type that needs it.
- **links**: logic `structure-is-one-contract.md` (the contract a row
  does NOT sign; `CandidateLeg` is its receipt instead),
  `relational-structs.md` (the design sketch this corrects: its Row of
  §3 is a Schema, its generator of §6 is obligation (4)),
  `transaction.md` (the refresh doors `clearing`/`replacing` land
  beside the write face), `apps/cargo` ledger entry 17.

The engine is honest about its two directions: a goal takes
`Unifiable`, an answer comes back `Reified`, and the two never mix.
Java has no such distinction — a field is a field — and every attempt
to give a row type one field type that serves both ends was a lie in one
direction. The resolution is that the field is the variable, and the
variable is already both: it enters the goal as itself and it reads the
answer as the key of a `Solution`. A schema is therefore a selector, the
variables a row is built from and the mapping of one solution over them
into the row as Java reads it, plus the one thing the literal head needs
that the engine does not, the columns' names and flags in key order.

What it buys. One class per row type that is variables in and value out,
with the mapping free-form Java: `DeliveryDto`'s next activity, built
from three columns, is three lines in `map`, which no rebuild or
generator could express. Rule bodies unify the fields directly; there is
no cast and no hoisting. A wide cell is refused before the mapping, at
the solution, naming the key — a Java value has no place for an open
cell, and the refusal is the engine's, not each schema's. The stored and
the derived face of a materialized row are still two literals over one
row, so `replacing(stored, derived)` keeps its type. And the refusal of a
projected() member moved from an eager pldb check to the same open-cell
gate, since a marker selected is a variable nothing bound.

What it does NOT buy. A row is not a term: two rows do not unify as
wholes, and `matche` takes no row arm. A type that travels inside a
term (the port's `CandidateLeg`, a leg inside the route finder's list)
signs the structural contract with `Term` members and goes through
`Term.unifiable()` to enter a goal; that door stays for exactly that
tier. Nor does it buy generation (obligation 4). And the engine's
`select` deduplicates by default where `solve` did not; the port's reads
all want distinct rows, and `all()` is one call away.

Cheapest way to kill it: a row type that must be both a selector and a
term — selected as values AND carried inside a list. Then either it is
two classes (the port's `Leg` and `CandidateLeg` already are, and the
seam is one `LegDto` apart) or the contract would have to let a
Structural's members be `Unifiable`, which the reifier forbids. The
first is the answer until it hurts.

Lineage, so the two green designs this replaces are not rebuilt. First,
`Schema<R> extends Structural<R>` with `Unifiable` fields: the port's
reads went through the engine's reifier, which rebuilds a structure
through `withMembers` with an `Any` in every open cell, and a
`Unifiable<T>` field cannot hold one — a `ClassCastException` the tests
never reached because every selected row was ground. Second, `Term`
fields with `Term.unifiable()` at every use in a rule body: sound, and
twenty cast lines across the port's six row types. The human's reading
that ended it: `Structural` is the honest term tier and should keep
`Term`; a schema is the Java interop layer, it should not be a
Structural at all, and the merged type Java needs already exists in the
engine as the variable read through a `Solution`. Promoted to logic as
`Selector` because it is the whole of the Java interop story, and more
important than `Structural` itself.
