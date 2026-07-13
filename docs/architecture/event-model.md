# The Event Model

The editor surfaces its internal activity — content changes, selection moves, document lifecycle,
LSP notifications — to the host application as a stream of **events** delivered through
[RxJS](https://rxjs.dev/). A host subscribes with `editorRef.getEvents().subscribe(…)`. This document
catalogs every event and explains how events flow.

## How events flow

Each editor owns an RxJS `ReplaySubject` (created in `lib.core`). Internal producers
(`lib.editor.runtime`, `lib.editor.commands`, `lib.core`) push events through `emit-event`, which
applies a **per-type debounce** so that high-frequency events (like `selection-change`) do not flood
subscribers. Inbound **LSP** events arrive on the *Workspace's* shared `:lsp-events` subject and are
fanned in to *every* pane's subject; each pane forwards them to its host subscription.

![Event flow: the runtime, commands, and core producers push through a per-type-debounced emit-event into the per-editor ReplaySubject; the LSP client emits onto the Workspace-shared :lsp-events subject which is fanned in to every pane's subject; the host subscribes via getEvents().](diagrams/event-flow.svg)

A `ReplaySubject` replays its most recent value(s) to late subscribers, so a host that subscribes
shortly after mount still observes recent events (notably `ready`).

## The event catalog

There are **21** event types. Each event is an object `{type, data}`. The authoritative TypeScript
definitions are the `EditorEvent` union in `types/lib.d.ts`; the table below mirrors them.

| `type` | `data` payload | Meaning |
|--------|----------------|---------|
| `ready` | `{}` | The editor is initialized and its imperative methods are usable. |
| `content-change` | `{content, uri}` (API-driven) or `{uri, length}` (keystroke) | Document text changed. The keystroke variant carries only the length, never the full text — see [syntax-and-editing.md](syntax-and-editing.md#the-keystroke-hot-path). |
| `selection-change` | `{cursor, selection, uri}` | Cursor and/or selection moved. **This is the cursor channel** (there is no separate cursor event). |
| `document-open` | `{uri, content, language, activated}` | A document was opened (and possibly activated). |
| `document-close` | `{uri}` | A document was closed. |
| `document-rename` | `{oldUri, newUri}` | A document's URI changed. |
| `document-save` | `{uri, content}` | A document was saved (`didSave` sent if connected). |
| `lsp-message` | `{method, lang, params?}` | A raw inbound LSP message (also emitted for outbound method context). |
| `lsp-initialized` | `{lang}` | The LSP `initialize`/`initialized` handshake completed. |
| `diagnostics` | `Array<Diagnostic>` (event also carries top-level `uri`/`version`/`lang`) | Diagnostics were published for a document. |
| `symbols` | `Array<Symbol>` | Document symbols were returned. |
| `log` | `{message, lang}` | A log line (surfaced in the demo app's logs panel). |
| `connect` | `{lang}` | A language server WebSocket opened. |
| `disconnect` | `{lang}` | A language server WebSocket closed. |
| `lsp-error` | `{message, lang, …}` | An LSP transport or protocol error. |
| `highlight-change` | `{from, to}` or `null` | A range highlight was set (or cleared). See the [opt-in note](syntax-and-editing.md#range-highlight-is-opt-in). |
| `language-change` | `{uri, language}` | A document's language changed. |
| `scroll` | `{from, to}` | The view scrolled/centred on a range. |
| `destroy` | `{}` | The editor was unmounted/destroyed. |
| `error` | `{message, operation, …}` | An imperative method threw; the error is surfaced rather than crashing the host. |
| `search-term-change` | `{term, uri}` | The search term changed. |

`Diagnostic` and `Symbol` payload shapes are documented in [data-model.md](data-model.md#the-schema)
and mirrored in `types/lib.d.ts`.

## Positions in events

Positions in event payloads (`cursor`, `selection.from/to`, `highlight-change`, `scroll`) are
**1-based** (line and column start at 1), matching the imperative API. Diagnostic and symbol
coordinates, which come from LSP, are **0-based** (as the LSP specification defines them). This split
is intentional and is documented per method in `types/lib.d.ts`.

## A vestigial non-event: `cursor-change`

The internal debounce configuration and the demo app's event handler both mention a `"cursor-change"`
case, but the library **never emits `cursor-change`** — cursor movement rides on `selection-change` —
and `cursor-change` is **absent** from the `EditorEvent` union in `types/lib.d.ts`. It is leftover
configuration, documented here so consumers do not wait for an event that will not arrive. Subscribe
to `selection-change` for cursor updates.

## Subscribing (host side)

```jsx
useEffect(() => {
  if (editorRef.current?.isReady()) {
    const sub = editorRef.current.getEvents().subscribe((evt) => {
      switch (evt.type) {
        case 'diagnostics': /* update your UI */ break;
        case 'selection-change': /* evt.data.cursor is 1-based */ break;
        default: break;
      }
    });
    return () => sub.unsubscribe();
  }
}, [editorRef]);
```

See [React Integration](../guide/react-integration.md) for the full subscription pattern and a worked
example.

## Related reading

- [editor-component.md](editor-component.md) — where the `events` subject is created and subscribed.
- [lsp-subsystem.md](lsp-subsystem.md) — the origin of the LSP-related events.
- [React Integration](../guide/react-integration.md) — consuming events from JavaScript/TypeScript.
