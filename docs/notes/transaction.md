# The transaction — AS BUILT: a write is a claim about the reads that justified it

A command reads the world, decides, and writes. The write is only sound
if the reads still hold at commit — between the two, a concurrent
commit may have changed what the decision stood on (both librarians see
copy 1 free; both lend it; each step locally correct, the outcome a
state the rules refuse: write skew). The transaction is the value that
carries this obligation: a READ FACE that records every probe, a WRITE
FACE that stages facts, and a commit that must PROVE the recorded reads
unmoved before the staged facts land. Proof is not the transaction's
job — it is a capability the SOURCE declares, by type, in one of two
kinds:

- **native serialization** (`NativeSerialization`, transaction
  `Native`): the backend validates read sets itself — an honest
  SERIALIZABLE (PostgreSQL's SSI, the two-phase-locking
  implementations). The transaction records nothing; the source's one
  commit door lands the flush and recognizes its backend's refusal
  dialect. Renting: zero schema demands, works on tables you don't
  own; the price is TRUST — the honesty is semantic and no code can
  check it (Oracle's SERIALIZABLE is snapshot isolation in costume and
  admits write skew; it must not wear the interface).
- **simulated serialization** (`SimulatedSerialization`, transaction
  `Simulated`): serialization reproduced above the backend. `pin(region)`
  names ONE region's version, captured at the region's FIRST touch —
  the footprint is pinned, never the world, so nothing enumerates the
  source's surface; `commit(footprint, flush)` runs one short
  transaction of the source's own — take the commit lock, prove every
  footprint region unmoved since ITS pin, land the flush, record the
  movement — or answer false with nothing landed. THE ORDERING IS THE
  SOUNDNESS: a pin is captured before the reads it certifies
  (capture-after would pass the proof on stale data under a fresh
  pin); a snapshot-reading source satisfies it for free, a
  snapshot-less one (a REST resource and its ETag) must mint the
  validator with the response. Owning: works on any backend with a
  working row lock, deterministic conflicts, testable without a
  container; the price is one private table.

A source with neither capability has no `over` to call — the refusal
is a compile error, because a transaction exists FOR its write face;
reads alone never need one.

Two connections, two DATABASE transactions, on the simulated tier: the
read connection holds one LONG-LIVED snapshot transaction — opened and
anchored at `pinned` (REPEATABLE READ or stronger), it is what read
stability and the pin's meaning stand on, and it lives until `close()`
ends it (an abandoned one is idle-in-transaction and blocks DDL — the
PG hang that forced `Library.close`). The commit is a SECOND, short
transaction on a fresh connection: lock, prove, flush, advance,
commit, gone. The native tier has one connection and one transaction —
the snapshot's own, which its backend both stabilizes and certifies.

## The pieces

- **`WriteBuffer`** (the old Overlay): a frozen base plus a private
  staged delta, read as one source — the value semantics of an
  immutable store over a base that is merely shared. Appends mint new
  values; ancestors and siblings stay true; same-key answers ⊕-fold in
  a JoinMap cell. `staged()` is the flush list. The delta is a signed
  set: retracting a fact this transaction staged, or asserting one it
  staged to remove, cancels it rather than refusing — replacing a value
  within the transaction that staged it leaves only the replacement, and
  only what remains against the base is flushed.
- **`Pin`**: an opaque region-version token — minted by a source,
  meaningful only handed back to it. Nothing else ever interprets one.
- **`Footprint`**: the regions a transaction read, each with the pin
  captured at its first touch — the probes (`Call<Relation>`) its
  literals minted, collected at the one seam every read path crosses
  (applied literals, the trial's posted probes, aggregate sub-solves
  all bottom out in `answers(Call)`). The RECORDER is the `Simulated`
  transaction's own `answers` override — pin first, then delegate the
  read; the WriteBuffer beneath it only unions, and the `Native`
  transaction records nothing. Recording and reading are the same act,
  so an unrecorded read is unrepresentable (`EVERYTHING` died with the
  world pin) and the EMPTY footprint certifies vacuously — a decision
  that stood on no reads cannot have stood on stale ones. One shared
  monotone map per transaction: any read may justify any staged fact —
  an over-approximation that costs retries, never soundness.
  Batch-level footprints were built and torn down: prefixes of one
  monotone log certify identically to the log itself.
- **`Conflict`**: the commit's refusal — the world moved past this
  snapshot; the caller's move is an ordinary re-solve against a fresh
  transaction, where the anomaly reappears as a named denial.

## The instances

- **`Watermark`** (simulated, any SQL): a private
  `watermark(relation, mark)` table with one lock row — `'*'`, the
  commit lock (one `FOR UPDATE` row, portable, single lock order),
  bumped every commit as the global movement counter. `pin(region)` is
  one row's SELECT through the snapshot connection (pin-before-read
  for free); certify compares each footprint pin against the current
  mark, and a mark row is minted by a relation's first write, so
  absence on BOTH sides proves the relation unmoved — double absence
  is silence, not blindness. The commit runs on a FRESH connection,
  because proving "unmoved" means reading the CURRENT world and a
  snapshot by definition refuses to show it (found as an H2 deadlock,
  not derived).
- **`SerializableSource`** (native, JDBC): grants SERIALIZABLE, vouches
  for its backend, recognizes SQLSTATE 40001 (the standard shared by
  PG, MySQL, SQL Server) or a supplied dialect.
- **`SharedDatabase`** (simulated, in-memory): the degenerate ideal —
  one cell of persistent Database values plus per-relation
  generations; the value IS the snapshot (read stability by
  construction), the monitor is the commit lock, and absence is exact
  knowledge where SQL had to be conservative.

The pin's isolation floor: `SqlFetch.pinned` raises a weaker level to
REPEATABLE READ and KEEPS a stronger one — unconditional setting would
have silently downgraded the rented tier back to snapshot isolation.

## The granularity ladder (precision, never soundness)

global mark → per-relation marks (SHIPPED — a book commit does not
bounce a person writer) → region matching (a delta row would have
answered a recorded probe; the compatibility filter and the pushdown
compiler already exist for it) → condition certify (impose the
decision's own conditional answer on the delta — the minimal conflict
set, waits on conditional answers having a consumer story). Each rung
is bought only by a measured false-conflict rate the rung below cannot
carry.

## considered, built, and torn down

- The `deciding(lambda)` door — reads bound to writes by scoping a
  command in a closure. Causally precise, ergonomically wrong ("I'm
  passing my logic into a lambda for execution"); the shared log
  replaced it.
- Per-batch footprints and per-append pin vectors — prefixes of one
  monotone log; certifying the union ≡ certifying the log.
- Fact-level provenance — a Fact is a value; provenance would enter
  equality and break set semantics. Parked for the provenance
  semiring.
- The backend honesty registry (product-name → honest?) — capability
  by construction replaced discovery by sniffing: the factory that
  mints a source is the party that knows.
- The certify vocabulary itself — "certify/rented/owned" served the
  design conversation and retired at the rename: what a source
  declares is which SERIALIZATION it brings.
- `getConnection` on the transaction — each capability owns its whole
  commit door; the machinery never touches JDBC (which is what let it
  move to `pldb.transaction` and admit the in-memory instance).

## The library receipt

`Library.over(Transaction)`, `commit()` and `close()` on the facade —
the domain unchanged to the byte. The double checkout: both values
validate copy 1 against their own snapshots, both pass, the first
commit wins, the second meets `Conflict`, and the retry answers
"copy not available" — write skew turned into an ordinary named
denial, receipted in memory (milliseconds) and on real PostgreSQL
through BOTH serializations. The one API gap the migration surfaced:
`Library` wrapped an AutoCloseable transaction without a close door —
harmless in memory, a forever-hang on PG (idle-in-transaction blocks
DDL). The facade closes now.

-----
- **status**: SHIPPED (Sep 2026), receipted end to end: WriteBuffer
  value semantics; write skew refused and disjoint relations passing
  on H2 (no container) and in memory; the PG suite through native
  serialization; snapshot stability at open; the library's
  double-checkout-to-denial on memory and PG through both kinds;
  refusal-without-capability enforced by javac.
- **imports**: write skew / serialization anomaly (Berenson et al.'s
  taxonomy; demonstrated by the deterministic receipts, not merely
  cited); SSI as PostgreSQL's SERIALIZABLE (Cahill; leaned on as the
  rented tier's landlord — the trust boundary is documented at
  `NativeSerialization`); optimistic concurrency control's
  read-validate-write shape (Kung–Robinson) as the simulated tier's
  ancestry.
- **remaining obligations**: (1) the region rung — trigger: a measured
  false-conflict rate per-relation marks cannot carry. (2) REST — the
  protocol maps to ETag/If-Match/412 almost verbatim, but waits on the
  async read seam (#64) and an as-of-version read contract (without
  it, reads tear between pin and commit). (3) multi-source commits —
  one solve reading two sources has no single landlord; the deferred
  coordinator. (4) `Conflict extends Exception` (checked) — deliberate
  ruling owed. (5) cross-key subsumption in WriteBuffer's union (a
  wide delta row shadowing a different base key).
- **links**: domain-layer.md §5 (freeze-and-certify — the freeze
  shipped as the pinned snapshot, the certify as the serialization
  kinds), table-as-the-source.md (the cache story's pins paragraph),
  the library NOTES entries 11/13/15 and their receipt.
