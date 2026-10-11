# Working on `pldb`

Relations over external data for the `logic` miniKanren engine: a relation lookup is a
constraint, answers are facts, and the transaction is the only session there is.
Java 8, vavr, lombok. Auto-loaded every session — read before changing code.

## Workflow rules (same as the logic repo)

- TDD; `mvn test` must end `BUILD SUCCESS` (265 tests) before any commit/merge. The
  Postgres tests run under Testcontainers — Docker must be up; H2 covers the rest.
- Branch per task; `git add` explicit paths (never `-A`); ff-merge to master; push only
  when asked.
- `logic` resolves from Central at the released version in the POM. To build against a
  local logic change, point the dependency at logic's `-SNAPSHOT`, `mvn install` logic
  (and `functional` beneath it if it moved), and restore the released version before
  merging. `apps/library` consumes pldb the same way.

## Architecture by package

- `org.clauseway.pldb` — the contracts. `AnswerSource` answers a `Call<Relation>` probe
  inline; `AnswerProducer` is the async kind (rows as a `Fiber`, `AnswerProducer.of` lifts a
  source); `GoalProducer` runs a rule through the solve's table; `Writer` asserts/retracts.
- `relations` — the schema and the question. `Literal.relation(ns, name).arg(col, x)…`
  then `.from(source)` / `.produced(producer)` / `.solving(goal)`; `Property<T>` is a typed
  column (`indexed()`, `ground()`, `nullable()`); `RelationN` the relation value;
  `Question.select(goal, templates…)` lands answers as rows; `Answers` the row algebra;
  `MagicVar`/`Projected` are marker variables one consumer recognises.
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
- **SQL NULL only where declared.** A null cell in a column that is not `nullable()`
  refuses by relation and column; a compiler pushing a comparison over a nullable column
  must handle NULL rows itself (three-valued logic under-delivers silently).
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
