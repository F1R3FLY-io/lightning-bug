#!/usr/bin/env node
/**
 * Documentation linter — guards `docs/` (and every tracked Markdown file) against two
 * regressions that PlantUML and GitHub silently tolerate but that corrupt the rendered
 * output. Both are things `-failfast2` / GitHub's renderer do NOT flag, so they ship unnoticed.
 *
 *   Check A — deprecated-activity-color  (*.puml)
 *     PlantUML's activity "colour prefix" syntax `#RRGGBB:label;` is deprecated. Recent
 *     PlantUML bakes a "This syntax is deprecated…" banner INTO the SVG (and, in 1.2026.x,
 *     drops the fill) while still exiting 0, so the broken diagram ships unnoticed. The
 *     supported form moves the colour to a trailing stereotype: `:label; <<#RRGGBB>>`.
 *
 *   Check B — code-wrapped-dollar-math  (*.md)
 *     GitHub renders inline math as a backtick code span wrapped IN dollar signs —
 *     `$` `` ` `` `x^2` `` ` `` `$`  (dollar, backtick, LaTeX, backtick, dollar). Writing it the
 *     other way round — backtick, `$`, LaTeX, `$`, backtick — produces an inert code span that
 *     shows the literal `$x^2$`, never math; the dollars are consumed as ordinary text and the
 *     LaTeX is never handed to MathJax. This check flags that transposed form. (The display
 *     variant — a `$$…$$` run wrapped in backticks — is reported too, but must be converted to
 *     a fenced ```math block by hand, so it is not auto-fixed.)
 *
 * Usage:
 *   node scripts/lint-docs.js                 # check every tracked .puml/.md; exits 1 on any violation
 *   node scripts/lint-docs.js --check         # explicit form of the default
 *   node scripts/lint-docs.js --fix           # auto-rewrite every deterministic violation, then
 *                                             #   report anything that still needs a manual fix
 *   node scripts/lint-docs.js a.puml docs/b.md   # lint only the given files (paths relative to CWD)
 *
 * Exit 0 = clean (or every violation auto-fixed); 1 = violations remain; 2 = usage error.
 *
 * False-positive guards (no per-line pragma is needed for the common cases):
 *   * Check A: only a line whose first non-space token is `#<hex>:` matches. PlantUML comments
 *     use a leading `'`, so nothing else in a `.puml` begins with `#` — zero false positives.
 *   * Check B: a match must contain a LaTeX metacharacter (`\ ^ _ { }`). This excludes
 *     meta-documentation that merely *shows* the forbidden syntax with a placeholder such as
 *     `$…$` (e.g. docs/development/contributing.md). Fenced code blocks (``` / ~~~) are skipped
 *     entirely. As a last resort, a `<!-- lint:allow-math -->` marker placed on the offending
 *     line (an HTML comment is invisible in rendered Markdown) suppresses Check B for that line.
 */

import { readFileSync, writeFileSync, readdirSync } from 'node:fs';
import { dirname, join, relative, isAbsolute } from 'node:path';
import { fileURLToPath } from 'node:url';
import { execFileSync } from 'node:child_process';
import process from 'node:process';

const SCRIPT_DIR = dirname(fileURLToPath(import.meta.url));
const ROOT = dirname(SCRIPT_DIR); // project root (parent of scripts/)

// ── CLI ─────────────────────────────────────────────────────────────────────
const argv = process.argv.slice(2);
const FIX = argv.includes('--fix');
const flags = argv.filter((a) => a.startsWith('--'));
const explicitPaths = argv.filter((a) => !a.startsWith('--'));
const badFlags = flags.filter((f) => f !== '--fix' && f !== '--check');
if (badFlags.length > 0) {
  console.error(`lint-docs: unknown flag(s): ${badFlags.join(' ')}`);
  console.error('usage: node scripts/lint-docs.js [--check | --fix] [file.puml … file.md …]');
  process.exit(2);
}
const toAbs = (p) => (isAbsolute(p) ? p : join(process.cwd(), p));

// ── File discovery ───────────────────────────────────────────────────────────
// Prefer `git ls-files` so we honour the repository's whitelist `.gitignore` (only tracked
// docs are linted); fall back to a directory walk when this is not a git checkout.
const IGNORE_DIRS = new Set(['node_modules', '.git', 'target', 'dist', '.shadow-cljs', '.cpcache']);

function walk(dir, ext, out) {
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    if (entry.isDirectory()) {
      if (!IGNORE_DIRS.has(entry.name)) walk(join(dir, entry.name), ext, out);
    } else if (entry.isFile() && entry.name.endsWith(ext)) {
      out.push(join(dir, entry.name));
    }
  }
  return out;
}

function trackedFiles(ext) {
  try {
    const stdout = execFileSync('git', ['-C', ROOT, 'ls-files', '--', `*${ext}`], { encoding: 'utf8' });
    const files = stdout.split('\n').filter(Boolean).map((p) => join(ROOT, p));
    if (files.length > 0) return files.sort();
  } catch {
    // not a git checkout (or git unavailable) — fall through to a filesystem walk
  }
  return walk(ROOT, ext, []).sort();
}

// ── Check A: deprecated PlantUML activity-colour prefix ───────────────────────
// A leading (optionally indented) `#<colour>:` activity prefix, where <colour> is a 3/4/6/8-digit
// CSS hex value OR a colour name (`#red:`, `#GreenYellow:`) — both are deprecated in favour of a
// trailing `<<#colour>>` stereotype. Safe against false positives: nothing else in a `.puml` begins
// with `#` (PlantUML line comments use a leading `'`).
const PUML_DEPRECATED =
  /^(\s*)#([0-9A-Fa-f]{8}|[0-9A-Fa-f]{6}|[0-9A-Fa-f]{4}|[0-9A-Fa-f]{3}|[A-Za-z][A-Za-z0-9]*):(.*)$/;

function processPuml(relPath, text, fix) {
  const violations = [];
  const lines = text.split('\n');
  for (let i = 0; i < lines.length; i++) {
    const m = PUML_DEPRECATED.exec(lines[i]);
    if (!m) continue;
    const [, indent, color, rest] = m;
    violations.push({
      file: relPath,
      line: i + 1,
      col: indent.length + 1,
      kind: 'deprecated-activity-color',
      excerpt: `#${color}:`,
      fixable: true,
      suggestion: `:<label>; <<#${color}>>`,
    });
    if (fix) lines[i] = `${indent}:${rest} <<#${color}>>`;
  }
  return { violations, text: lines.join('\n') };
}

// ── Check B: code-wrapped dollar math (transposed GFM inline/display math) ─────
const FENCE = /^\s*(`{3,}|~{3,})(.*)$/;
const ALLOW_MATH = /<!--\s*lint:allow-math\s*-->/;
const LATEX_META = /[\\^_{}]/; // a real LaTeX construct: command, super/sub-script, or grouping
// A run of one-or-more `$` on each side, wrapped in single backticks: `` `$…$` `` or `` `$$…$$` ``.
const DOLLAR_IN_BACKTICKS = /`(\$+)([^`\n]*?)(\$+)`/g;

function inlineFix(content) {
  // The GitHub-recommended inline form: backticks INSIDE the dollars.
  return `$\`${content}\`$`;
}

function processMarkdown(relPath, text, fix) {
  const violations = [];
  const lines = text.split('\n');
  let fence = null; // the active fence marker char ('`' or '~') while inside a code block
  for (let i = 0; i < lines.length; i++) {
    const line = lines[i];

    const fenceMatch = FENCE.exec(line);
    if (fenceMatch) {
      const marker = fenceMatch[1][0];
      if (fence === null) fence = marker; // opening fence
      else if (fence === marker && fenceMatch[2].trim() === '') fence = null; // matching close
      continue; // never scan the fence delimiter line itself
    }
    if (fence !== null) continue; // inside a fenced code block
    if (ALLOW_MATH.test(line)) continue; // explicit per-line escape hatch (same line only)

    DOLLAR_IN_BACKTICKS.lastIndex = 0;
    let m;
    while ((m = DOLLAR_IN_BACKTICKS.exec(line)) !== null) {
      const [span, open, content, close] = m;
      if (!LATEX_META.test(content)) continue; // placeholder / meta-doc — not real math
      const display = open.length >= 2 && close.length >= 2;
      const fixableInline = !display && open.length === 1 && close.length === 1;
      violations.push({
        file: relPath,
        line: i + 1,
        col: m.index + 1,
        kind: display ? 'code-wrapped-display-math' : 'code-wrapped-dollar-math',
        excerpt: span,
        fixable: fixableInline,
        suggestion: fixableInline
          ? inlineFix(content)
          : display
            ? 'convert to a fenced ```math block (display math is not an inline span)'
            : 'use matched single `$`/backtick delimiters: `$`…`$` (backticks inside dollars)',
      });
    }

    if (fix) {
      const rewritten = line.replace(DOLLAR_IN_BACKTICKS, (span, open, content, close) => {
        if (!LATEX_META.test(content)) return span;
        if (open.length === 1 && close.length === 1) return inlineFix(content);
        return span; // display / unbalanced runs need a manual, structural fix
      });
      if (rewritten !== line) lines[i] = rewritten;
    }
  }
  return { violations, text: lines.join('\n') };
}

// ── Drive both checks ─────────────────────────────────────────────────────────
let pumlFiles;
let mdFiles;
if (explicitPaths.length > 0) {
  pumlFiles = explicitPaths.filter((p) => p.endsWith('.puml')).map(toAbs);
  mdFiles = explicitPaths.filter((p) => p.endsWith('.md')).map(toAbs);
  for (const p of explicitPaths.filter((p) => !p.endsWith('.puml') && !p.endsWith('.md'))) {
    console.error(`lint-docs: ignoring unsupported file (need .puml or .md): ${p}`);
  }
} else {
  pumlFiles = trackedFiles('.puml');
  mdFiles = trackedFiles('.md');
}

const allViolations = [];
let filesRewritten = 0;

function run(files, processor) {
  for (const abs of files) {
    const rel = relative(ROOT, abs);
    const text = readFileSync(abs, 'utf8');
    const { violations, text: next } = processor(rel, text, FIX);
    allViolations.push(...violations);
    if (FIX && next !== text) {
      writeFileSync(abs, next);
      filesRewritten += 1;
    }
  }
}

run(pumlFiles, processPuml);
run(mdFiles, processMarkdown);

// In --fix mode, every `fixable` violation was just rewritten; only non-fixable ones remain.
const remaining = FIX ? allViolations.filter((v) => !v.fixable) : allViolations;
const fixedCount = FIX ? allViolations.length - remaining.length : 0;

console.log(`lint-docs: scanned ${pumlFiles.length} .puml and ${mdFiles.length} .md file(s).`);

if (FIX && fixedCount > 0) {
  console.log(`lint-docs: auto-fixed ${fixedCount} violation(s) across ${filesRewritten} file(s).`);
  console.log('lint-docs: re-run `npm run docs:diagrams` to regenerate SVGs for any fixed .puml source.');
}

if (remaining.length === 0) {
  console.log('lint-docs: ✓ documentation clean.');
  process.exit(0);
}

console.error(`\nlint-docs: ✗ ${remaining.length} violation(s)${FIX ? ' still need a manual fix' : ''}:\n`);
for (const v of remaining) {
  console.error(`  ${v.file}:${v.line}:${v.col}: ${v.kind}: ${v.excerpt}`);
  console.error(`      fix: ${v.suggestion}`);
}

const byKind = {};
for (const v of remaining) byKind[v.kind] = (byKind[v.kind] || 0) + 1;
console.error('\n  summary:');
for (const [kind, n] of Object.entries(byKind).sort((a, b) => b[1] - a[1])) {
  console.error(`    ${String(n).padStart(4)}  ${kind}`);
}
console.error('\n  reference — docs guidelines:');
console.error('    • inline math  →  `$`…`$`  (backticks INSIDE the dollars)');
console.error('    • display math →  a fenced ```math block');
console.error('    • activity colour → `:label; <<#RRGGBB>>` (trailing stereotype, not `#RRGGBB:label;`)');
if (!FIX) console.error('\n  run `npm run lint:docs -- --fix` to apply the deterministic fixes.');
process.exit(1);
