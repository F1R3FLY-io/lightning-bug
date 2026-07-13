# Contributing

Thank you for contributing to Lightning Bug. This guide covers coding style, commit conventions, the
testing bar, the lint gate your change must pass, and two easy-to-miss gotchas (documentation
diagrams and the `.gitignore` whitelist). It complements [Building & Testing](./building-and-testing.md),
which explains how to build and run everything.

**Acronyms.** CI = Continuous Integration; LSP = Language Server Protocol; DRY = Don't Repeat
Yourself; PR = Pull Request; FSM = finite-state machine.

---

## Workflow at a glance

1. **Branch from `main`.** Name the branch for its intent (for example
   `feature/multi-editor-workspaces` or `fix/lsp-shutdown-race`).
2. **Make focused changes** with tests and documentation alongside the code.
3. **Run the local gate before pushing:**

   ```bash
   npm run lint     # clj-kondo + eastwood + splint + kibit  (must be clean)
   npm test         # types -> debug -> release -> demo build -> demo sanity
   ```

4. **Open a PR against `main`,** referencing any issue it closes and describing the change and its
   tests. CI (see [Building & Testing → CI pipeline](./building-and-testing.md#ci-pipeline)) runs
   the same gate across every supported OS and browser.

Contributions must be compatible with the project's Apache-2.0 license.

---

## Coding style

- **Follow the Clojure style guide.** Prefer clear, idiomatic Clojure/ClojureScript; let the linters
  (below) catch the mechanical issues.
- **Use kebab-case for keys and identifiers** — `:document/active-uri`, `open-document`,
  `connection-manager`. This is uniform across the source and the DataScript schema.
- **Group and order namespace imports:** core/standard first, third-party next, project-local last.
- **Prefer pattern/`case`/`cond` dispatch over deep predicate nesting** where it reads more clearly,
  and keep functions small and single-purpose.
- **Consolidate dependencies** where reasonable, and prefer modern, maintained libraries over
  bespoke code.
- **Keep comments and docstrings current.** Add them where they are missing; do not delete existing
  documentation unless you are improving its readability. Public API surfaces (the `<Editor>` props,
  ref methods, and RxJS events) must stay in sync with the TypeScript bindings in `types/` — validate
  with `npm run test:types` (see [TypeScript bindings](../guide/typescript-bindings.md)).
- **Be careful in LSP / WebSocket handling.** This is the main external trust boundary; apply
  security updates promptly and validate inbound messages. See the
  [security overview](../security/README.md).

---

## Commit messages — Conventional Commits

Use [Conventional Commits](https://www.conventionalcommits.org/): a `type(scope): summary` subject,
where `type` is one of `feat`, `fix`, `docs`, `refactor`, `test`, `perf`, `build`, `ci`, or `chore`.

```
feat(lsp): auto-start stale-request cleanup on connect
fix(editor): keep per-pane cursor when the same file syncs
docs(development): add building-and-testing guide
```

Keep the subject imperative and under ~72 characters; put rationale and context in the body. Scope
should name the affected subsystem (`lsp`, `editor`, `workspace`, `db`, `formal`, `docs`, …).

---

## Testing bar

New behavior ships with tests. The suite is currently **516 tests** under `src/test/` (see
[Building & Testing → Running the tests](./building-and-testing.md#running-the-tests)).

- **Add the right tier(s):** unit tests for pure logic, integration tests for cross-subsystem flows
  (document lifecycle, LSP, multi-editor coordination), and adapter tests for repository code.
- **Property-based tests** for anything with an interesting input space. Use `clojure.test.check`
  and **pin the seed** (`{:seed 42}`) so a failure is reproducible in CI and locally — several
  existing suites (for example `src/test/lib/position_property_test.cljs`) follow this pattern.
- **Both compiles must pass.** Your tests run under `:none` (`test:debug`) and `:advanced`
  (`test:release`); avoid constructs that only work before Closure `:advanced` renaming.
- Aim for full coverage of the new feature — including its failure and edge paths, not just the happy
  path.

---

## The lint gate

`npm run lint` runs four Clojure linters in sequence; **all four must be clean** for CI's
`lint-and-test-types` job to pass. Their configuration lives in
[`deps.edn`](../../deps.edn) (the `:dev` aliases), `.clj-kondo/config.edn`, and `.eastwood.edn`.

| Linter | Script | What it enforces |
|--------|--------|------------------|
| [clj-kondo](https://github.com/clj-kondo/clj-kondo) | `npm run lint:clj-kondo` | Static analysis: unused/undeclared vars, arity errors, shadowing, unresolved symbols. |
| [Eastwood](https://github.com/jonase/eastwood) | `npm run lint:eastwood` | Deeper lint: reflection warnings, suspicious expressions, deprecations. |
| [Splint](https://github.com/NoahTheDuke/splint) | `npm run lint:splint` | Idiom and style rules. |
| [kibit](https://github.com/jonase/kibit) | `npm run lint:kibit` | Suggests idiomatic rewrites (e.g. `(if x y nil)` → `(when x y)`). |

Run a single linter while iterating (for example `npm run lint:clj-kondo`), then the full `npm run
lint` before pushing.

---

## Adding documentation and diagrams

Documentation is part of the change, not an afterthought. When you add or modify behavior, update the
relevant docs under `docs/` (architecture, guide, formal, security, or development, as appropriate)
and cross-link with **relative** paths (`./sibling.md`, `../section/doc.md`, `../README.md`).

To add a diagram, author a PlantUML `.puml` source in the section's `diagrams/` folder, render it,
and commit both source and SVG. The full procedure — including the bare-`@startuml` convention and
the shared colour legend — is in
[Building & Testing → Documentation diagrams](./building-and-testing.md#documentation-diagrams).
In short:

```bash
# after editing docs/<section>/diagrams/<name>.puml
npm run docs:diagrams        # requires PlantUML + Graphviz on PATH
git add docs/<section>/diagrams/<name>.puml docs/<section>/diagrams/<name>.svg
```

Follow the [documentation guidelines](../README.md#how-this-documentation-is-built): define acronyms
before use, write mathematics as MathJax (a backtick-wrapped dollar span for inline; a fenced block
with the `math` info string for display — never bare `$…$`), and prefer PlantUML over Mermaid.

---

## The `.gitignore` whitelist gotcha

Lightning Bug's [`.gitignore`](../../.gitignore) is a **blacklist-by-default, whitelist-by-pattern**
file. The first rule ignores everything (`*`); directories are re-included with `!*/` so that files
inside them can be individually whitelisted; then each file type the repository tracks is added back
with an explicit `!`-prefixed rule, for example:

```gitignore
*                       # ignore everything
!*/                     # …but descend into directories
!/src/**/*.cljs         # track ClojureScript sources
!/docs/**/*.md          # track Markdown docs
!/docs/**/*.puml        # track PlantUML sources
!/docs/**/*.svg         # track rendered diagrams
```

**Consequence:** if you add a file whose type or location is not covered by an existing whitelist
rule, git **silently ignores it** — it will not appear in `git status` and your PR will be missing
files. If a file you just created is not staged and you cannot explain why, this is almost certainly
the reason: add the appropriate `!/path/pattern` rule to `.gitignore` first. (This is exactly why the
`!/docs/**/*.puml` and `!/docs/**/*.svg` rules exist — without them, committed diagrams would
vanish.)

---

## Review bar

- **One approval** is required to merge.
- Reviewers focus on **readability, maintainability, DRY, and separation of concerns**, in addition to
  correctness.
- The PR must be **green in CI**: linters, type tests, formal verification, and the cross-platform
  browser matrix (and, on PRs, the benchmark regression tripwire) all pass.
- Use issue labels (`bug`, `enhancement`, …) and include reproduction steps for bug reports.

---

## See also

- [Building & Testing](./building-and-testing.md) — the toolchain, scripts, and CI.
- [Release process](./release-process.md) — how a version reaches GitHub Packages.
- [Documentation home](../README.md) — the docs map and guidelines.
