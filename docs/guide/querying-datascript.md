# Querying DataScript

Lightning Bug keeps its editor state — open documents, diagnostics, symbols, logs, the active URI,
and projects — in an in-memory [DataScript](https://github.com/tonsky/datascript) database, and lets
you query it with **Datalog**. This page is a short Datalog primer followed by the `query()` and
`getDb()` APIs, framed correctly for the multi-workspace model.

> **Terms used on this page.** **DataScript** = an immutable, in-memory database for
> Clojure/ClojureScript, inspired by [Datomic](https://www.datomic.com/). **Datalog** = a declarative,
> logic-programming query language (related to Prolog). **EAV** = Entity–Attribute–Value, the triple
> shape DataScript stores facts in. **conn** = a DataScript *connection* (a mutable reference to an
> immutable database value). **URI** = the string that names a document.

## Where the data lives: one database per Workspace

There is **no single global database**. Each **Workspace** owns its own DataScript connection, and an
editor queries the Workspace it belongs to:

- **`getDb()`** returns the **active Workspace's** DataScript connection.
- **`query(q, params?)`** runs `q` against that same connection.

Two editors that [share a Workspace](./react-integration.md#multi-editor-and-split-pane-workflows)
therefore query the *same* database (a document opened in one is visible to the other); editors in
distinct Workspaces query independent databases. Keep this in mind whenever you read snippets that
speak loosely of "the database" — it always means *this editor's Workspace database*.

`query` / `getDb` and their results are intentionally typed **`any`** in the TypeScript bindings:
Datalog result shapes depend on the query's `:find` clause, so no single static type fits. Narrow the
result at the call site (see [TypeScript Bindings](./typescript-bindings.md#the-intentionally-any-query--getdb-boundary)).

## A short Datalog primer

A DataScript database is a set of **facts**, each an **EAV** triple: *entity* (an internal id),
*attribute* (a namespaced keyword such as `:document/text`), and *value*. A Datalog query is a vector
of clauses; the two you will use most are:

- **`:find`** — what to return (the "head" of the query).
- **`:where`** — a conjunction of `[entity attribute value]` patterns to match. A symbol that appears
  in two patterns (say `?e`) must bind to the same entity in both — that is how joins happen.
- **`:in`** — names inputs; the database is the implicit first input `$`, and any parameters follow.

Special `:find` forms:

- `[:find ?x . …]` returns a **single scalar** (the trailing `.`).
- `[:find [?x …] …]` returns a **flat collection**.
- `[:find (count ?e) …]` uses an **aggregate**, returning a table like `[[N]]`.

Datalog is side-effect free: you describe *what* you want, not *how* to fetch it. For deeper study
see [Learn Datalog Today](https://www.learndatalogtoday.org/), the
[DataScript repository](https://github.com/tonsky/datascript), and
[Wikipedia: Datalog](https://en.wikipedia.org/wiki/Datalog).

## The schema you can query

The Workspace database stores these entity kinds (each tagged with a `:type`). The most useful
attributes:

| Entity (`:type`) | Key attributes |
|------------------|----------------|
| `:document` | `:document/uri` (unique), `:document/text`, `:document/language`, `:document/version`, `:document/dirty`, `:document/opened`, `:document/project` (ref, optional) |
| `:diagnostic` | `:diagnostic/document` (ref), `:diagnostic/message`, `:diagnostic/severity`, `:diagnostic/start-line`, `:diagnostic/start-char`, `:diagnostic/end-line`, `:diagnostic/end-char`, `:diagnostic/version` (optional) |
| `:symbol` | `:symbol/document` (ref), `:symbol/name`, `:symbol/kind`, `:symbol/start-line`, `:symbol/start-char`, `:symbol/end-line`, `:symbol/end-char`, `:symbol/selection-start-*`, `:symbol/selection-end-*`, `:symbol/parent` (ref, optional) |
| `:log` | `:log/message`, `:log/lang` |
| `:active-uri` | `:workspace/active-uri` (unique) |
| `:project` | `:project/id` (unique), `:project/root` (unique), `:project/name` (optional) |

`:document/uri`, `:workspace/active-uri`, `:project/id`, and `:project/root` are unique identities;
`:symbol/parent`, `:symbol/document`, `:diagnostic/document`, and `:document/project` are references
(they join to another entity). For the full schema and its design rationale, see
[Architecture: the data model](../architecture/data-model.md).

## `query(q, params?)`

Pass a Datalog query as a string (or an array of clauses) and an optional parameter array; get a
JavaScript array back. The path from your call to the connection is small: the string is parsed to
Clojure data, run with `d/q` against the active Workspace's `conn`, and the result is converted back
to JavaScript. Blue = your call site, teal = the editor command layer, slate = the Workspace, orange
= the DataScript connection.

![Sequence diagram: the JS caller invokes query(); the editor keywordizes the query, resolves the active workspace connection, runs d/q against it, converts the result to a JS array, and returns it; getDb() returns the raw connection for direct use with the datascript library.](diagrams/datascript-query.svg)

### The active document's text (no parameters)

The active URI is stored under `:workspace/active-uri`; join it to the document to fetch its text:

```javascript
const q =
  '[:find ?text . ' +
  ' :where [?a :workspace/active-uri ?uri]' +
  '        [?e :document/uri ?uri]' +
  '        [?e :document/text ?text]]';
const text = editorRef.current.query(q);
console.log('Active text:', text); // a single string (the `.` in :find returns a scalar)
```

### A specific document's text (with a parameter)

Name an input with `:in $ ?uri` and pass the URI in `params`:

```javascript
const q = '[:find ?text . :in $ ?uri :where [?e :document/uri ?uri] [?e :document/text ?text]]';
const params = ['inmemory:///demo.rho'];
const text = editorRef.current.query(q, params);
console.log('Specific text:', text);
```

### Counting with an aggregate

```javascript
const q = '[:find (count ?e) :where [?e :type :document] [?e :document/opened true]]';
const rows = editorRef.current.query(q);
console.log('Open documents:', rows[0][0]); // aggregate returns [[N]] -> read [0][0]
```

### Listing all diagnostics for a document

```javascript
const q =
  '[:find ?msg ?sev ?line ' +
  ' :in $ ?uri ' +
  ' :where [?d :diagnostic/document ?doc]' +
  '        [?doc :document/uri ?uri]' +
  '        [?d :diagnostic/message ?msg]' +
  '        [?d :diagnostic/severity ?sev]' +
  '        [?d :diagnostic/start-line ?line]]';
const rows = editorRef.current.query(q, ['inmemory:///demo.rho']);
// rows: Array<[message, severity, startLine]>
```

(For diagnostics and symbols you can also use the dedicated `getDiagnostics(uri?)` and
`getSymbols(uri?)` ref methods, which return typed arrays — see
[React Integration](./react-integration.md#lsp-methods).)

## `getDb()` and the DataScript library directly

`query()` uses DataScript's `d/q`, which does **not** perform *pull* expressions (the syntax for
fetching a nested tree of attributes). When you need pull — for example to materialize a symbol with
its nested children through `:symbol/parent` — get the raw connection with `getDb()` and call the
`datascript` library yourself. Add `datascript` to your project and import it (here as `ds`):

```javascript
// import * as ds from 'datascript';
const conn = editorRef.current.getDb(); // the active Workspace's connection

// Find a parent symbol's entity id...
const parentId = ds.q('[:find ?e . :where [?e :symbol/name "parent-symbol"]]', ds.db(conn));

// ...then pull it with its children (recursing on the :symbol/parent reference).
const pulled = ds.pull(ds.db(conn), '[* {:symbol/parent [*]}]', parentId);
console.log('Nested symbol:', pulled);
```

Because the connection is a live reference, always take a fresh database value with `ds.db(conn)`
when you read.

## Time-travel (advanced)

DataScript keeps immutable database values, so you can hold onto a `ds.db(conn)` snapshot and query
it later to compare against the present — handy for diffs and undo-style inspection. This is a
DataScript capability rather than a Lightning Bug feature; see
[DataScript internals](https://tonsky.me/blog/datascript-internals/).

## See also

- [Architecture: the data model](../architecture/data-model.md) — the complete schema and why it is
  shaped this way.
- [React Integration](./react-integration.md) — `query`, `getDb`, `getDiagnostics`, `getSymbols`.
- [TypeScript Bindings](./typescript-bindings.md) — why `query`/`getDb` are typed `any`.
- [LSP Configuration](./lsp-configuration.md) — where diagnostics and symbols come from.
