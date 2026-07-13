# Development

Engineering documentation for **contributors** to Lightning Bug — how to build it, test it, keep it
lint-clean, and ship a release. If you are *using* the editor rather than working on it, start with
the [Usage Guide](../guide/README.md) instead; if you want to understand the design, start with the
[Architecture Overview](../architecture/README.md).

This documentation reflects version `0.7.7`.

## In this section

| Document | What it covers |
|----------|----------------|
| **[Building & Testing](./building-and-testing.md)** | Prerequisites and install; the build/watch/release [targets](./building-and-testing.md#build-watch-and-release-targets); the full [npm-script](./building-and-testing.md#npm-scripts-reference) and [node-script](./building-and-testing.md#node-helper-scripts) reference; the [test tiers](./building-and-testing.md#running-the-tests) (**516 tests**); the [CI pipeline](./building-and-testing.md#ci-pipeline); and [documentation diagrams](./building-and-testing.md#documentation-diagrams) (`npm run docs:diagrams`). |
| **[Contributing](./contributing.md)** | Coding style (kebab-case, import ordering), [Conventional Commits](./contributing.md#commit-messages--conventional-commits), the [testing bar](./contributing.md#testing-bar), the four-linter [lint gate](./contributing.md#the-lint-gate), adding docs + diagrams, the [`.gitignore` whitelist gotcha](./contributing.md#the-gitignore-whitelist-gotcha), and the review bar. |
| **[Release Process](./release-process.md)** | Cutting a release (changelog → version bump → `v*` tag → GitHub Packages), what the `release` CI job gates on, and the [SemVer](./release-process.md#semantic-versioning-semver) policy. |

## Fast path

```bash
# One-time setup
export NODE_AUTH_TOKEN=your_pat_here   # GitHub PAT with read:packages
npm install && clojure -P && npm run prepare:all

# The gate you must pass before opening a PR
npm run lint     # clj-kondo + eastwood + splint + kibit
npm test         # types -> debug -> release -> demo build -> demo sanity
```

## Related sections

- [Formal Verification](../formal/README.md) — the TLA+/Rocq models and the
  [verification gate](../formal/verification-gate.md) that gates release.
- [Benchmarks](../benchmarks/README.md) — the performance methodology behind the CI regression gate.
- [Architecture](../architecture/README.md) — the system design you are contributing to.
- [Documentation home](../README.md) — the full docs map and authoring guidelines.
