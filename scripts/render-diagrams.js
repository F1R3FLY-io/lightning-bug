#!/usr/bin/env node
// Renders every `docs/**/*.puml` PlantUML source to a sibling `.svg`.
//
// Rationale: the project documentation guidelines prefer PlantUML over Mermaid because
// PlantUML output is byte-reproducible and can typeset LaTeX. GitHub, however, does not
// render PlantUML source blocks, so diagrams are authored as `.puml` sources and committed
// as pre-rendered `.svg` (embedded via Markdown image links) so they display on GitHub.
//
// Conventions expected of each source (so output filenames are predictable):
//   * Exactly one `@startuml` / `@enduml` block per file.
//   * A BARE `@startuml` (no trailing name) — PlantUML then derives the output name from the
//     source filename, producing `<source>.svg` beside `<source>.puml`.
//
// Usage: `npm run docs:diagrams`  (or `node scripts/render-diagrams.js`).
// Exits non-zero if PlantUML fails or any expected `.svg` is not produced.

import { execFileSync } from 'node:child_process';
import { readdirSync, existsSync, readFileSync } from 'node:fs';
import { join, relative } from 'node:path';
import process from 'node:process';

const ROOT = process.cwd();
const DOCS_DIR = join(ROOT, 'docs');

/** Recursively collect every `.puml` path under `dir`. */
function findPuml(dir) {
  const found = [];
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    const full = join(dir, entry.name);
    if (entry.isDirectory()) found.push(...findPuml(full));
    else if (entry.isFile() && entry.name.endsWith('.puml')) found.push(full);
  }
  return found;
}

if (!existsSync(DOCS_DIR)) {
  console.error(`render-diagrams: no docs/ directory at ${DOCS_DIR}`);
  process.exit(1);
}

const sources = findPuml(DOCS_DIR).sort();
if (sources.length === 0) {
  console.log('render-diagrams: no .puml sources under docs/; nothing to render.');
  process.exit(0);
}

console.log(`render-diagrams: rendering ${sources.length} PlantUML diagram(s) to SVG…`);
// -tsvg     : emit SVG beside each source (PlantUML preserves each input's directory).
// -nometadata: strip embedded version/timestamp so output is byte-reproducible.
// -failfast2 : run a syntax pre-pass and abort on the first error.
try {
  execFileSync('plantuml', ['-tsvg', '-nometadata', '-failfast2', ...sources], {
    stdio: 'inherit',
    cwd: ROOT,
  });
} catch {
  console.error('render-diagrams: PlantUML rendering failed (is `plantuml` on PATH?).');
  process.exit(1);
}

// PlantUML bakes certain *non-fatal* problems INTO the SVG and still exits 0, so
// `-failfast2` (a hard-error pre-pass) does NOT catch them and a visibly broken diagram
// would ship silently. The known signatures:
//   * the "This syntax is deprecated…" banner emitted for the deprecated activity
//     colour prefix `#RRGGBB:label;` (the supported form is `:label; <<#RRGGBB>>` —
//     see scripts/lint-docs.js, Check A); and
//   * HTML-escaped stereotype/colour markup (`&lt;&lt;#…`, `&lt;back:…`) left as literal
//     text when PlantUML fails to parse a colour directive.
// Scan every rendered SVG for these and fail the build if any appear. (scripts/lint-docs.js
// catches the same class in the *source* pre-render; this is the belt-and-braces post-render
// guard that also catches any future PlantUML deprecation we have not enumerated by hand.)
// NB: PlantUML separates the words of its baked-in banner with non-breaking spaces
// (`This&#160;syntax&#160;is&#160;deprecated…`), so the SVG body is normalised (`&#160;` → ' ')
// before matching the banner phrase — a literal-space regex would silently miss it. The banner
// covers both hex and named colours (`#red:label;` is deprecated too); the `&lt;&lt;#…` / `&lt;back:`
// signatures additionally catch a stereotype/colour directive that leaked as literal text without
// a banner (e.g. a malformed `<<#…>>`).
const TAINT_SIGNATURES = [
  { re: /This syntax is deprecated/, why: 'deprecated PlantUML syntax banner baked into the SVG' },
  { re: /&lt;&lt;#/, why: 'HTML-escaped `<<#…` stereotype leaked as literal text' },
  { re: /&lt;back:/, why: 'HTML-escaped `<back:` colour markup leaked as literal text' },
];

let missing = 0;
let tainted = 0;
for (const src of sources) {
  const svg = src.replace(/\.puml$/, '.svg');
  if (!existsSync(svg)) {
    console.error(`  ✗ expected SVG not produced: ${relative(ROOT, svg)}`);
    missing += 1;
    continue;
  }
  const body = readFileSync(svg, 'utf8').replace(/&#160;|&#x[aA]0;/g, ' ');
  const hit = TAINT_SIGNATURES.find(({ re }) => re.test(body));
  if (hit) {
    console.error(`  ✗ ${relative(ROOT, svg)} — ${hit.why}`);
    tainted += 1;
  } else {
    console.log(`  ✓ ${relative(ROOT, svg)}`);
  }
}

if (missing > 0 || tainted > 0) {
  if (missing > 0) {
    console.error(`render-diagrams: ${missing} diagram(s) did not produce the expected SVG ` +
      '(check that each source uses a bare `@startuml` and a single diagram block).');
  }
  if (tainted > 0) {
    console.error(`render-diagrams: ${tainted} rendered SVG(s) carry baked-in PlantUML ` +
      'warnings/errors — fix the .puml source (e.g. replace a deprecated `#RRGGBB:label;` ' +
      'activity colour with `:label; <<#RRGGBB>>`, then re-render). `npm run lint:docs` ' +
      'flags this in the source before rendering.');
  }
  process.exit(1);
}

console.log(`render-diagrams: all ${sources.length} diagram(s) rendered and clean.`);
