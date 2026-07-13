# The Data Model — DataScript & Datalog

Lightning Bug keeps its editor state in **DataScript**, an immutable, in-memory database for
ClojureScript that stores facts as **Entity–Attribute–Value (EAV)** tuples and queries them with
**Datalog**. Each Workspace owns one DataScript connection (`conn`); this document describes the
schema, the query conventions, and the public querying seam.

## Why DataScript?

DataScript ([github.com/tonsky/datascript](https://github.com/tonsky/datascript)) is modelled on
Datomic. Editor state is naturally relational — documents have diagnostics and symbols; symbols
nest; documents belong to projects — and Datalog expresses those relationships declaratively:

- **EAV tuples.** Every fact is `[entity attribute value]`. There is no fixed table shape; an entity
  simply has whatever attributes have been asserted about it.
- **Datalog.** A logic query language (a relative of Prolog) that states *what* to retrieve, not
  *how*. Queries are data (vectors of clauses), so they compose and are easy to reason about. See
  the [Datalog primer](../guide/querying-datascript.md) in the usage guide for a worked introduction.
- **Immutability + transactions.** Updates are atomic transactions producing a new immutable
  database value; reads never block writers.

## The schema

The schema is defined in `lib.db` (`src/lib/db.cljs`). Entities are discriminated by a `:type`
keyword plus namespaced attributes. Full `clojure.spec` definitions accompany each entity and are
validated on every transaction under a `DEBUG` compile flag.

![Entity-relationship diagram of the schema: document (keyed by unique document/uri) has many diagnostics and many symbols; symbols form a self-referential parent hierarchy; documents belong to a project by longest root-prefix; a singleton active-uri entity names the focused URI; log entries are standalone.](diagrams/data-model-er.svg)

| Entity (`:type`) | Key attributes | Notes |
|------------------|----------------|-------|
| **`:document`** | `:document/uri` *(unique identity)*, `:document/text`, `:document/language` *(index)*, `:document/version` *(index)*, `:document/dirty`, `:document/opened` *(index)*, `:document/project` *(ref, index)* | `:opened` records that the LSP `didOpen` has been sent. |
| **`:diagnostic`** | `:diagnostic/document` *(ref, index)*, `:diagnostic/message`, `:diagnostic/severity`, `:diagnostic/start-line`, `:diagnostic/start-char`, `:diagnostic/end-line`, `:diagnostic/end-char`, `:diagnostic/version` *(index, optional)* | Severity: 1 = Error, 2 = Warning, 3 = Info, 4 = Hint. A `nil` version "always matches". |
| **`:symbol`** | `:symbol/document` *(ref, index)*, `:symbol/parent` *(ref, self)*, `:symbol/name`, `:symbol/kind`, `:symbol/{start,end}-{line,char}`, `:symbol/selection-{start,end}-{line,char}` | `kind` is the LSP standard symbol kind. The hierarchy is flattened on insert using negative temp-ids with `:symbol/parent` links. |
| **`:project`** | `:project/id` *(unique identity)*, `:project/root` *(unique identity)*, `:project/name` *(optional)* | See [Projects](#projects). |
| **`:log`** | `:log/message`, `:log/lang` | The entries surfaced in the demo app's logs panel. |
| **`:active-uri`** *(singleton)* | `:workspace/active-uri` *(unique identity)* | The Workspace focus. |

### The active-URI singleton

The Workspace focus is a single entity carrying `:workspace/active-uri`. Because that attribute is
`:db.unique/identity`, `update-active-uri!` retracts only *other* active-uri entities rather than
issuing a blanket retract — a blanket retract would blank the just-set focus. (This precise bug
surfaced when a second pane re-activated the file that was already focused; the fix is in the schema
note on the diagram above.)

### Projects

A project groups documents under a root URI. On document creation, `create-documents!` links each
document to the project whose `:project/root` is the **longest URI prefix** of the document's URI —
so a document maps to its most specific enclosing project. The accessors are `create-projects!`,
`project-for-uri`, `documents-by-project`, and `project-of-uri`. See
[multi-editor-workspaces.md](multi-editor-workspaces.md#the-projects-model).

## Query conventions

`lib.db` follows a documented naming convention that encodes a performance decision (see the
[performance model](../benchmarks/performance-model.md)):

### Coalesced queries

**`doc-*` / `active-uri-*`** accessors return *multiple* attributes from a **single** Datalog query,
to avoid the N+1 cost of issuing several single-attribute reads for one logical read. These are the
hot-path reads; the repositories (and the demo app's coeffects) prefer them. Examples:

- `active-uri-text-lang-version` → `[uri text lang version]`
- `active-uri-version` → `[uri version]`
- `doc-text-lang-version-by-uri` → `[text lang version]`
- `document-id-lang-opened-by-uri`, `document-language-opened-by-uri`, …

This "coalesce multi-attribute reads" pattern is the `EXP-007` optimization; the archived record is
[`../archive/benchmarks/EXP-007_query-coalescence.md`](../archive/benchmarks/EXP-007_query-coalescence.md).

### Single-attribute accessors

**`document-*`** accessors return one attribute (e.g. `document-text-by-uri`,
`document-version-by-uri`, `document-language-by-uri`, `document-opened-by-uri?`). They remain for
call sites that genuinely need one value.

### Batch reads with `pull-many`

Diagnostics and symbols are read in batches with DataScript's `d/pull-many` over a set of entity ids,
handling optional attributes (like a symbol's parent) in ClojureScript *after* the query. This
replaced an in-query `or-join`, which the DataScript optimizer handled poorly — the single most
important query optimization in the project (`EXP-002`/`EXP-003`; see the
[performance model](../benchmarks/performance-model.md#ids-then-pull-many)).

## The query cache is dormant

`lib.query-cache` (`src/lib/query_cache.cljs`) implements a TTL- and size-bounded query-result cache
with transaction-counter invalidation. **It is intentionally *not* wired into `lib.db` or any runtime
path** — it is required only by the benchmark harness and its own test, and there is no `d/listen`
transaction hook in the runtime. The active hot-path strategy is coalesced queries (above), so **all
runtime reads are always fresh**; the cache is an opt-in facility kept for possible future use.
Documenting this prevents the mistaken assumption that reads are cached.

## The public querying seam

The editor exposes DataScript to the host application through two ref methods (see
[Querying DataScript](../guide/querying-datascript.md) for worked examples):

- `query(q, params?)` runs a Datalog query (`d/q`) against the active Workspace's database and
  returns the result as a JavaScript array.
- `getDb()` returns the DataScript connection for advanced use (e.g. `d/pull` for nested symbols),
  when the host imports `datascript` directly.

Both are typed `any` in `types/lib.d.ts` on purpose: a Datalog query's result shape depends on its
`:find` clause, so no single static type applies. The host narrows the result at the call site.

Because the `conn` is Workspace-scoped, these methods operate on the DataScript database of *this
editor's Workspace* — there is no global connection. Two editors on distinct Workspaces query
distinct databases.

## Reads and writes in practice

```clojure
;; A coalesced read: the active document's text, language, and version in ONE query.
(db/active-uri-text-lang-version @conn)
;; => [uri text lang version]

;; A transaction: mark a document opened (LSP didOpen sent).
(d/transact! conn [[:db/add eid :document/opened true]])

;; A batch read: all symbols for a URI, via pull-many + post-query parent handling.
(db/symbols-by-uri @conn uri)
```

## Related reading

- [lsp-subsystem.md](lsp-subsystem.md) — how diagnostics and symbols arrive and are written here.
- [Querying DataScript](../guide/querying-datascript.md) — the Datalog primer and public-API examples.
- [Performance Model](../benchmarks/performance-model.md) — why coalesced queries and `pull-many`.
