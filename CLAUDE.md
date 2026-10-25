# Working on `pldb`

Relations over external data for the `logic` miniKanren engine: a relation lookup is a
constraint, answers are facts, and the transaction is the only session there is.
Java 8, vavr, lombok. Auto-loaded every session — read before changing code.

## Workflow rules (same as the logic repo)

- TDD; `mvn test` must end `BUILD SUCCESS` (296 tests) before any commit/merge. The
  Postgres tests run under Testcontainers — Docker must be up; H2 covers the rest.
- Branch per task off `develop`; `git add` explicit paths (never `-A`); a branch lands in
  `develop` through a pull request Tom merges, `develop` lands in `master` the same way
  and that merge IS the release; push only when asked.
- Every merge to `develop` publishes the POM's `-SNAPSHOT` to the Central Portal's
  snapshot repository (`snapshot.yml`); the merge to `master` releases the version minus
  `-SNAPSHOT` once, by tag (`release.yml`). Central allows a handful of releases a month
  across all three artifacts, so a release is a milestone Tom names, never a step to make
  a merge legal. The first branch after a release bumps `develop`'s POM to the next
  `-SNAPSHOT`.
- `logic` resolves from Central. On `master` the POM names a released version; on
  `develop` it may name logic's `-SNAPSHOT`, which logic's `develop` publishes and this
  POM's `<repositories>` block resolves. The `develop` → `master` pull request restores
  the released version. `mvn install` logic locally only for a change not yet merged to
  its `develop`. `apps/cargo` and `apps/library` consume pldb the same way.

## Architecture by package

- `org.clauseway.pldb` — the contracts. `AnswerSource` answers a `Call<Relation>` probe
  inline; `AnswerProducer` is the async kind (rows as a `Fiber`, `AnswerProducer.of` lifts a
  source); `GoalProducer` runs a rule through the solve's table; `Writer` asserts/retracts.
- `relations` — the schema and the question. `Schema<D>` is a logic `Selector<D>` plus
  `columns()`: a row type's fields are its keys (`Unifiable`), `describe()` names its columns
  over them in one chain (`keys()` and `columns()` are read off it), `map(Solution)` is the row
  as Java reads it, `Query.select(row).stream()` streams the values; `Schema.builder().arg(name,
  x)….build()` is an anonymous row (`Schema<Solution>`), each step typed to offer only what
  may follow it. `Property<T>` is a typed column
  (`indexed()`, `ground()`); `RelationN` the relation value; `Answers` the row algebra;
  `MagicVar`/`Projected` are marker variables one consumer recognises.
- `relations.literals` — `Literal.relation(ns, name)` opens a `LiteralBuilder`: `.schema(row)`
  for a typed row, or `.arg(name, x)` chains naming an anonymous row on the literal
  (`Columns`); then `.from(source)` / `.produced(producer)` / `.solving(goal)` (`Backend<S>`).
  A flag before a column or a literal without a backend does not compile. `Literal<S>` is the read type, `External<S>` the
  write face (`fact()`; a rule is not one); the kinds are package-private;
  `Question.select(query, templates…)` lands answers as rows for the stores and the write
  doors (`Writer.clearing`/`replacing` ride it).
- `constraints` — the lookup AS a constraint. `TableConstraints.posted` parks a
  `TablePropagator` (sync) or `TableParkingPropagator` (async/rule); `Support` is the finite
  domain a free column narrows to; `labelo` enumerates.
- `inmemory` — `AnswerStore` (an immutable, indexed fact value) and `SharedDatabase` (one
  history of store values; `open(id)` is a `SimulatedSerialization` snapshot).
- `sql` — by CONVENTION: table = relation name, columns = property names. `SqlFetch`
  (`pinned`/`live`) compiles probe + constraints to `SELECT … WHERE` (`FiniteDomainSqlCompiler`,
  `NogoodSqlCompiler`); `CachingSqlFetch` adds the coverage ledger; `SqlFlush` lands rows;
  `Codecs` map values to the wire. Serializations: `SerializableSource` (native, honest
  SERIALIZABLE), `Watermark` (per-relation marks, `schema(admin)` is the DDL),
  `VersionedWatermark` (region grain, needs a `version` column).
- `transaction` — `Transaction.over(NativeSerialization)` leans on the backend's proof;
  `Transaction.recording(SimulatedSerialization)` yields a `RecordingTransaction` whose
  `footprint()` of `Pin`s outlives it and returns as a `requiring(premise)`. `WriteBuffer`
  stages the delta; `Conflict` is the only refusal.

## Landmines

- **Property and Relation identity is VALUE-based (name + flags).** Two
  `Property.of("water")` are the same column; a copy-pasted constant silently reads the
  wrong one. See `docs/design/database-ergonomics.md` before touching identity.
- **The proof kind is chosen by door, checked by type.** `over` wants a
  `NativeSerialization`, `recording` a `SimulatedSerialization`; a source with neither has
  no door — that absence is deliberate, do not add a fallback.
- **SQL NULL only through an optional codec.** An absent cell is `LOption.none()`, never
  a null; a column that may be NULL is `Unifiable<LOption<T>>` with `Codec.optional(inner)`
  registered via `withCodec`, and a NULL in a column with no optional codec refuses by
  relation and column. The region compiler reads the probe's shape (`none()` → IS NULL,
  `some(v)` → `= ?`, `some(free)` → IS NOT NULL); a comparison on the inner value stays
  engine-side, so three-valued logic never meets a NULL row.
- **Register compilers and codecs before first use** on `CachingSqlFetch` — the ledger's
  containment proof assumes a fixed registry.
- `Watermark.schema(admin)` must run before the first transaction opens; a
  `VersionedWatermark` table without its `version` column refuses at first read.

## Where knowledge lives

- `docs/design/domain-layer.md` — the north star: the engine at the data boundary.
  **Start here.** Then `external-relations.md` (the backend contract), `table-constraints.md`
  (lookups as a narrowing store), `database-ergonomics.md`, `query-planning.md`,
  `deferred-lookups.md`, `relational-structs.md`, `further-directions.md`.
- `docs/notes/` — AS-BUILT receipts: `transaction.md` (a write is a claim about the reads
  that justified it), `table-as-the-source.md`, `wire-face.md`, `persist.md`,
  `pins-stay-off-engine.md`, `namespaced-relations.md`. `TEMPLATE.md` for new ones.
