# Styling the Editor

Lightning Bug is themed entirely with **CSS** (Cascading Style Sheets) — you never modify the
component to restyle it. This page catalogs every customizable class, explains how to layer your
styles over the defaults, and shows worked overrides, including how to combine the editor with
[Bootstrap](https://getbootstrap.com/) utilities.

> **Terms used on this page.** **CSS** = Cascading Style Sheets. **CodeMirror** = the underlying
> editor engine (<https://codemirror.net/>); most classes are prefixed `cm-`. **Gutter** = the strip
> beside the text that holds line numbers. **Diagnostic** = an LSP error/warning/info/hint (see
> [LSP Configuration](./lsp-configuration.md)).

## The default look

Out of the box the editor uses a **dark theme** and fills its parent element. Because it fills its
parent, the parent must have a size — wrap the editor in an element with an explicit height (and
width, if not block-level):

```jsx
<div className="code-editor" style={{ height: '400px' }}>
  <Editor ref={editorRef} languages={{ rholang: RholangExtension }} />
</div>
```

The default extension stack contributes the pieces you will style: line numbers, bracket matching,
the editable content area, syntax coloring (driven by each language's Tree-Sitter highlights query;
see [Language Extensions](./language-extensions.md)), and diagnostic underlines.

## The class catalog

Every customizable class from the default theme:

### Layout and structure

| Class | Styles |
|-------|--------|
| `.code-editor` | The wrapper around the whole editor. Set **height/width** here. |
| `.cm-editor` | The CodeMirror container — background and borders. |
| `.cm-content` | The editable text area — font, padding, text color. |
| `.cm-gutters` | The gutter that holds line numbers — background, alignment. |
| `.cm-lineNumbers .cm-gutterElement` | Individual line-number cells — alignment, padding. |

### Syntax highlighting

These classes are applied to tokens according to the active language's highlights query, so they
only take visible effect for a language that supplies one (the default `"text"` language does not).

| Class | Token role |
|-------|-----------|
| `.cm-keyword` | Keywords |
| `.cm-number` | Numeric literals |
| `.cm-string` | String literals |
| `.cm-boolean` | Boolean literals |
| `.cm-variable` | Variables |
| `.cm-comment` | Comments |
| `.cm-operator` | Operators |
| `.cm-type` | Types |
| `.cm-function` | Functions |
| `.cm-constant` | Constants |

### Diagnostics and highlight

| Class | Meaning |
|-------|---------|
| `.cm-error-underline` | Error diagnostic underline (severity 1) |
| `.cm-warning-underline` | Warning diagnostic underline (severity 2) |
| `.cm-info-underline` | Info diagnostic underline (severity 3) |
| `.cm-hint-underline` | Hint diagnostic underline (severity 4) |
| `.cm-highlight` | Background for a highlighted range |

> **`.cm-highlight` caveat.** This class only becomes visible if a range-highlight decoration is
> actually mounted. The plugin that paints `.cm-highlight` is **not** in the default extension stack,
> so `highlightRange()` styles nothing on its own — you must render the decoration yourself (or add a
> range-highlight extension via `extraExtensions`). Full explanation in
> [React Integration](./react-integration.md#highlight-and-scroll).

## Layering your styles over the defaults

Load your stylesheet **after** the library's default CSS so your rules win on equal specificity.
Because several default rules are themselves specific, you will often need `!important` to override
them reliably:

```css
/* your-theme.css, loaded after the default theme */
.cm-content {
  font-family: 'Monaco', ui-monospace, monospace !important;
  background-color: #f0f0f0 !important;
  color: #333 !important;
}

.cm-keyword   { color: #d73a49 !important; }
.cm-string    { color: #032f62 !important; }
.cm-comment   { color: #6a737d !important; font-style: italic !important; }

.cm-highlight { background-color: #fff3cd !important; }
```

To size the editor, style the wrapper rather than the internal CodeMirror nodes:

```css
.code-editor {
  height: 70vh;
  border: 1px solid #ccc;
  border-radius: 6px;
  overflow: hidden;
}
```

## A worked light theme

A compact, self-contained light theme overriding the dark defaults:

```css
.cm-editor            { background: #ffffff !important; border: 1px solid #e1e4e8 !important; }
.cm-content           { color: #24292e !important; }
.cm-gutters           { background: #f6f8fa !important; color: #959da5 !important; border: none !important; }

.cm-keyword           { color: #d73a49 !important; }
.cm-number            { color: #005cc5 !important; }
.cm-string            { color: #032f62 !important; }
.cm-boolean           { color: #005cc5 !important; }
.cm-variable          { color: #24292e !important; }
.cm-comment           { color: #6a737d !important; font-style: italic !important; }
.cm-operator          { color: #d73a49 !important; }
.cm-type              { color: #6f42c1 !important; }
.cm-function          { color: #6f42c1 !important; }
.cm-constant          { color: #005cc5 !important; }

.cm-error-underline   { text-decoration: underline wavy #cb2431 !important; }
.cm-warning-underline { text-decoration: underline wavy #b08800 !important; }
.cm-info-underline    { text-decoration: underline wavy #0366d6 !important; }
.cm-hint-underline    { text-decoration: underline dotted #6a737d !important; }
```

## Theming with Bootstrap utilities

If your app uses Bootstrap, you can lay out and frame the editor with utility classes on the wrapper
(the editor itself is styled through the classes above, not Bootstrap):

```html
<div class="container-fluid p-0">
  <div class="code-editor border rounded h-100">
    <!-- <Editor /> renders here -->
  </div>
</div>
```

Useful utilities include sizing (`h-100`, `w-100`, `vh-100`), spacing (`p-0`, `m-2`), and color
(`bg-dark`, `text-light`) for the surrounding chrome. Bootstrap governs your layout; the `cm-*`
classes govern the editor's interior.

## See also

- [React Integration](./react-integration.md) — `extraExtensions` (add your own CodeMirror theme or
  plugins) and the highlight caveat.
- [Language Extensions](./language-extensions.md) — the highlights queries that assign the `cm-*`
  syntax classes.
- [LSP Configuration](./lsp-configuration.md) — where diagnostics (the underline classes) come from.
