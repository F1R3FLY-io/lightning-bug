# Release Process

Lightning Bug is published to the **GitHub Packages** npm registry as
[`@f1r3fly-io/lightning-bug`](https://github.com/f1R3FLY-io/lightning-bug). A release is cut by
tagging a commit; the tag push triggers the `release` job in
[`.github/workflows/ci.yaml`](../../.github/workflows/ci.yaml), which publishes the package. This
document describes the steps, the automation that runs them, and the Semantic Versioning (SemVer)
policy that decides the next version number.

**Acronyms.** SemVer = Semantic Versioning; CI = Continuous Integration; CD = Continuous Delivery;
PAT = Personal Access Token; API = Application Programming Interface.

---

## Steps to cut a release

The current released version is `0.7.7` (see [`package.json`](../../package.json)).

1. **Update `CHANGELOG.md`.** Move the accumulated entries from the `[Unreleased]` section into a new
   version section headed `## [X.Y.Z] - YYYY-MM-DD`, grouped under the
   [Keep a Changelog](https://keepachangelog.com/en/1.0.0/) categories (**Added**, **Changed**,
   **Deprecated**, **Removed**, **Fixed**, **Security**). Leave a fresh, empty `[Unreleased]` above
   it.
2. **Bump the version in `package.json`** to `X.Y.Z`, following the SemVer rules in
   [§3](#semantic-versioning-semver). Keep it consistent with the CHANGELOG heading.
3. **Commit** the changelog + version bump, e.g. `git commit -m "chore(release): prepare vX.Y.Z"`.
4. **Merge to `main`** through the normal PR flow so the tagged commit is on the release branch and
   has passed CI.
5. **Tag the release:**

   ```bash
   git tag vX.Y.Z
   ```

6. **Push the tag:**

   ```bash
   git push origin vX.Y.Z
   ```

   This triggers the GitHub Actions **release** job, which builds and publishes the package to GitHub
   Packages.
7. **Verify** the published version appears under the repository's Packages and that the release is
   green in the Actions tab.

> The tag must match the `v*` pattern (for example `v0.7.8`). The `release` job's condition is
> `github.event_name == 'push' && startsWith(github.ref, 'refs/tags/v')`, so only a pushed `v…` tag —
> not a branch push — publishes.

---

## What the automation does

The tag push does not publish blindly. The `release` job **`needs: [lint-and-test-types,
formal-verification, test]`**, so it runs only after those three jobs are green on the tagged commit:

| Upstream job | Must pass because |
|--------------|-------------------|
| **lint-and-test-types** | code is lint-clean and the TypeScript bindings type-check. |
| **formal-verification** | the source LSP FSM still aligns with the TLA+/Rocq models and the Rocq proofs compile (`verify:formal:ci`). |
| **test** | the full Karma suite and demo sanity pass across every supported OS × browser × compile-mode. |

Only then does `release` download the `libs-artifact` produced by the `test` job (the built `dist/`,
`types/`, extension resources, and `scripts/utils.js`) and run:

```bash
npm publish --access public
```

against the GitHub Packages registry (`https://npm.pkg.github.com/`, configured in `.npmrc` and
`publishConfig`), authenticated with the workflow's `NODE_AUTH_TOKEN`. Note that the **benchmark** job
is *not* a release dependency — it is a pull-request-only regression tripwire. For the full job graph
see [Building & Testing → CI pipeline](./building-and-testing.md#ci-pipeline) and, for the formal
gate specifically, the [verification gate](../formal/verification-gate.md).

---

## Semantic Versioning (SemVer)

This project adheres to [Semantic Versioning 2.0.0](https://semver.org/). A version is
`MAJOR.MINOR.PATCH`:

| Component | Increment when… | Effect |
|-----------|-----------------|--------|
| **MAJOR** | you make an incompatible (breaking) public-API change | reset MINOR and PATCH to 0 (e.g. `1.4.2` → `2.0.0`) |
| **MINOR** | you add backward-compatible functionality (or deprecate) | reset PATCH to 0 (e.g. `1.4.2` → `1.5.0`) |
| **PATCH** | you make a backward-compatible bug fix | bump PATCH only (e.g. `1.4.2` → `1.4.3`) |

The **public API** for versioning purposes is the surface documented in the TypeScript bindings and
the guide: the `<Editor>` component props, the imperative ref methods, the RxJS event shapes, the
exported workspace/extension factories, and the DataScript query entry points. A change that alters
any of these incompatibly is a MAJOR change.

**Pre-release and build metadata.** A pre-release is a hyphen-suffixed identifier
(`1.0.0-alpha`, `1.0.0-alpha.1`) that signals instability and sorts *before* the corresponding
release. Build metadata is a `+`-suffixed identifier (`1.0.0+20130313144700`) that does **not** affect
precedence.

**The `0.y.z` phase.** While the MAJOR version is `0` (as Lightning Bug is today at `0.7.7`), the API
is considered unstable: anything may change between releases, and `0.y.z` increments do not carry the
compatibility guarantees that begin at `1.0.0`. Once a version is published its contents are
immutable — any change requires a new version number.

---

## See also

- [Building & Testing](./building-and-testing.md) — the CI jobs the release depends on.
- [Contributing](./contributing.md) — Conventional Commits and the changelog discipline that feeds a
  clean release.
- [Formal verification gate](../formal/verification-gate.md) — the `formal-verification` job that
  gates `release`.
