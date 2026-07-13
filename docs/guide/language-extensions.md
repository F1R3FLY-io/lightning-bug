# Language Extensions

Lightning Bug is decoupled from any particular language. You teach it a language by passing a
**`LanguageConfig`** in the `languages` prop — a map from a language key (a string such as
`"rholang"`) to a configuration object. This page documents every field, the bundled Rholang
extension, how to override defaults, and how to add a brand-new language.

> **Terms used on this page.** **WASM** = WebAssembly, the binary format the
> [Tree-Sitter](https://tree-sitter.github.io/tree-sitter/) grammar runs as in the browser.
> **Grammar** = the Tree-Sitter parser definition for a language. **Query** = a Tree-Sitter
> [S-expression query](https://tree-sitter.github.io/tree-sitter/using-parsers/queries/index.html)
> (in a `.scm` file) that maps syntax nodes to highlight or indentation roles. **LSP** = Language
> Server Protocol (see [LSP Configuration](./lsp-configuration.md)). **Thunk** = a zero-argument
> function used to defer or compute a value.

## The `LanguageConfig` interface

Exactly as declared in [`types/lib.d.ts`](../../types/lib.d.ts):

```typescript
export interface LanguageConfig {
  /** Path or function returning path to Tree-Sitter grammar WASM. */
  grammarWasm?: string | (() => string);
  /** Custom parser function or instance (bypasses grammarWasm). */
  parser?: Parser | (() => Parser) | (() => Promise<Parser>);
  /** Path or function returning path to highlights query. */
  highlightsQueryPath?: string | (() => string);
  /** Raw highlights query string (alternative to path). */
  highlightsQuery?: string;
  /** Path or function returning path to indents query. */
  indentsQueryPath?: string | (() => string);
  /** Raw indents query string (alternative to path). */
  indentsQuery?: string;
  /** URL for LSP server. */
  lspUrl?: string;
  /** File extensions associated with this language. */
  extensions: string[];
  /** Icon for files of this language (CSS class, e.g. `"fas fa-code"`). */
  fileIcon?: string;
  /** Fallback highlighter if Tree-Sitter fails ('none' or 'regex'). */
  fallbackHighlighter?: string;
  /** Indentation size in spaces. */
  indentSize?: number;
}
```

Field-by-field:

| Field | Required | Meaning |
|-------|----------|---------|
| `extensions` | **yes** | File extensions this language claims, for example `[".rho"]`. Used to pick a language from a file name. |
| `grammarWasm` | no | URL (or thunk) to the language's Tree-Sitter grammar WASM. Omit for a language with no grammar (plain-text editing). |
| `parser` | no | A ready `Parser` (or a thunk returning one, possibly async). Supplying it **bypasses `grammarWasm`** — use this when you already have a configured [`web-tree-sitter`](https://www.npmjs.com/package/web-tree-sitter) parser. |
| `highlightsQueryPath` / `highlightsQuery` | no | The highlights query, given either as a path/thunk to a `.scm` file **or** inline as a raw string. Drives syntax coloring. |
| `indentsQueryPath` / `indentsQuery` | no | The indentation query, as a path/thunk **or** raw string. Drives auto-indentation. |
| `lspUrl` | no | WebSocket URL of a language server. Present ⇒ the editor connects and lights up diagnostics/symbols; absent ⇒ the editor works without them. |
| `fileIcon` | no | A CSS class for the file's icon (for example a [Font Awesome](https://fontawesome.com/) class like `"fas fa-file-code"`). Used by host UIs that render file lists. |
| `fallbackHighlighter` | no | What to do if Tree-Sitter fails to load: `"none"` (plain text) or `"regex"` (a coarse regex highlighter). |
| `indentSize` | no | Indentation width in spaces. |

The configuration is normalized internally (the public camelCase keys map to the library's internal
kebab-case keys), and validated: a config missing the required `extensions` throws during validation.

### Dynamic resolution: thunks and data URLs

Any field typed `string | (() => string)` accepts a **thunk**. Returning the path lazily lets you:

- defer computing a URL until the language is actually used;
- return an in-memory **data URL** (a `data:`/`blob:` URL that inlines the bytes) so you do not have
  to host the WASM or `.scm` files separately.

The package ships exactly such thunks for the bundled Rholang assets:

```js
import { treeSitterRholangWasmUrl } from '@f1r3fly-io/lightning-bug/extensions/lang/rholang/tree-sitter';
import { highlightsQueryUrl, indentsQueryUrl }
  from '@f1r3fly-io/lightning-bug/extensions/lang/rholang/tree-sitter/queries';
// Each is a () => string returning a blob/data URL for the embedded asset.
```

The core Tree-Sitter runtime has an equivalent export,
`treeSitterWasmUrl()` from `@f1r3fly-io/lightning-bug/tree-sitter`, which you can pass to the
`treeSitterWasm` prop (see [React Integration](./react-integration.md#component-props-editorprops)).

## How a language resolves at runtime

When a document of language `L` is activated, the editor resolves `L`'s config in this order: a
custom `parser` short-circuits grammar loading; otherwise `grammarWasm` is loaded (if present);
highlight and indent queries are resolved (path/thunk or raw string); and, if `lspUrl` is set, an LSP
connection is opened. With no grammar at all, the language is plain-text editable. Purple = the
Tree-Sitter / WASM path, green = the optional LSP path, blue = your `languages` prop.

![Activity diagram: starting from the languages map, the editor either uses a custom parser, loads the grammar WASM, or falls back to plain text; then resolves highlight and indent queries; then optionally connects one LSP WebSocket per language per workspace; then the editor is ready for the language.](diagrams/language-resolution.svg)

## The bundled Rholang extension

Import `RholangExtension` (a ready `LanguageConfig`) and register it under a key you choose (by
convention `"rholang"`):

```js
import { RholangExtension } from '@f1r3fly-io/lightning-bug/extensions';
// <Editor languages={{ rholang: RholangExtension }} />
```

`RholangExtension` is defined in `src/ext/lang/rholang.cljs` and, in its JavaScript (camelCase) form,
is equivalent to:

```js
const RholangExtension = {
  grammarWasm: "/extensions/lang/rholang/tree-sitter/tree-sitter-rholang.wasm",
  highlightsQueryPath: "/extensions/lang/rholang/tree-sitter/queries/highlights.scm",
  indentsQueryPath: "/extensions/lang/rholang/tree-sitter/queries/indents.scm",
  lspUrl: "ws://localhost:41551",
  extensions: [".rho"],
  fileIcon: "fas fa-file-code text-primary",
  fallbackHighlighter: "none",
};
```

The default `grammarWasm` and query paths are **served paths**: they assume the WASM and `.scm`
files live under `/extensions/lang/rholang/tree-sitter/` on your host. Inside the Lightning Bug
repository, `npm run prepare:all` copies those assets into place (see
[Getting Started](./getting-started.md#providing-the-tree-sitter-wasm-assets)). In your own app you
either serve them at those paths or replace the paths with the embedded data-URL thunks shown above.
The default `lspUrl` points at a locally running `rholang-language-server` on port `41551`; if you do
not run one, the editor still highlights and indents Rholang — LSP is optional.

## Overriding defaults

The `languages` prop is merged over the built-in `"text"` language, so you only specify what you
need. To customize Rholang, spread `RholangExtension` and override fields:

```js
import { Editor } from '@f1r3fly-io/lightning-bug';
import { RholangExtension } from '@f1r3fly-io/lightning-bug/extensions';
import { treeSitterRholangWasmUrl } from '@f1r3fly-io/lightning-bug/extensions/lang/rholang/tree-sitter';
import { highlightsQueryUrl } from '@f1r3fly-io/lightning-bug/extensions/lang/rholang/tree-sitter/queries';

const languages = {
  rholang: {
    ...RholangExtension,                    // start from the bundled config
    grammarWasm: treeSitterRholangWasmUrl,  // inline the grammar via a data-URL thunk
    highlightsQueryPath: highlightsQueryUrl, // inline the highlights query
    lspUrl: 'ws://my-host:41551',           // point at your own server
    indentSize: 4,                          // widen indentation
  },
};

// <Editor languages={languages} />
```

## Adding a new language

Provide a fresh `LanguageConfig` under a new key. The only required field is `extensions`; supply a
grammar and queries for syntax support, and an `lspUrl` for language-server features. You may give
queries inline (`highlightsQuery` / `indentsQuery`) instead of by path.

```js
const languages = {
  // A minimal language with a lazily loaded grammar and an inline highlights query.
  mylang: {
    extensions: ['.mylang'],
    grammarWasm: () => '/grammars/tree-sitter-mylang.wasm', // thunk: resolved on first use
    highlightsQuery: '(identifier) @variable\n(comment) @comment',
    indentsQueryPath: '/grammars/mylang/indents.scm',
    lspUrl: 'ws://localhost:5007',
    fileIcon: 'fas fa-file-code',
    fallbackHighlighter: 'none',
    indentSize: 2,
  },
};

// <Editor languages={languages} />
```

If you already have a configured `web-tree-sitter` `Parser`, hand it over directly and skip
`grammarWasm`:

```js
const languages = {
  mylang: {
    extensions: ['.mylang'],
    parser: async () => await buildMyParser(), // () => Promise<Parser>
    highlightsQuery: '(identifier) @variable',
  },
};
```

Open a document with your language by naming it explicitly, or by using a matching file extension:

```js
editorRef.current.openDocument('example.mylang', 'source text here', 'mylang');
```

## The default `"text"` language

The library always includes a built-in `"text"` language, configured as `{ extensions: ['.txt'],
fallbackHighlighter: 'none' }`. It has **no grammar**, so documents in it are edited as plain text —
line numbers, bracket matching, undo/redo, and search all still work; there is simply no syntax
coloring or Tree-Sitter indentation. It is the sensible default for scratch buffers and for the
split-pane examples in [React Integration](./react-integration.md#multi-editor-and-split-pane-workflows).
Passing a `languages` map does not remove `"text"`; your entries are merged on top of it.

## See also

- [React Integration](./react-integration.md) — the `languages` prop in context.
- [LSP Configuration](./lsp-configuration.md) — running a server and setting `lspUrl`.
- [Styling](./styling.md) — the `.cm-keyword`, `.cm-string`, … classes that highlight queries drive.
- Architecture: [syntax & editing](../architecture/syntax-and-editing.md) for how Tree-Sitter is
  wired into CodeMirror.
