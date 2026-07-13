# LSP Configuration

The **Language Server Protocol (LSP)** is a JSON-RPC protocol, standardized by Microsoft, that lets
an editor talk to a language-analysis server for features like diagnostics (errors and warnings) and
document symbols. Lightning Bug speaks LSP **over a WebSocket**, and it is entirely **optional**: with
no server configured, the editor still highlights, indents, and edits; a server simply adds the
analysis features on top.

This page shows how to run the Rholang language server, point the editor at it, and reason about the
connection lifecycle.

> **Terms used on this page.** **LSP** = Language Server Protocol
> (<https://microsoft.github.io/language-server-protocol/>). **WebSocket** = a persistent, full-duplex
> browser-to-server connection. **Diagnostic** = an error/warning/info/hint the server reports about a
> document. **Symbol** = a named, located construct (contract, function, variable, …) the server
> extracts. **Workspace** = a shared editor context (see
> [React Integration](./react-integration.md#multi-editor-and-split-pane-workflows)).

## Running the Rholang language server

The bundled Rholang extension expects a
[`rholang-language-server`](https://github.com/f1R3FLY-io/rholang-language-server) listening on a
WebSocket. Build it from its repository, then launch it in WebSocket mode (the port is configurable;
`41551` is the default the extension targets):

```bash
git clone https://github.com/f1R3FLY-io/rholang-language-server.git
cd rholang-language-server
# build per that repository's instructions, then:
rholang-language-server --websocket --port 41551 --log-level debug
```

- `--websocket` selects the WebSocket transport (rather than stdio).
- `--port 41551` matches `RholangExtension`'s default `lspUrl` of `ws://localhost:41551`.
- `--log-level debug` is optional but useful while wiring things up; server log lines surface in the
  editor as `log` events.

## Pointing the editor at a server

A language connects to LSP when its `LanguageConfig` has an `lspUrl`. The bundled Rholang config
already sets `lspUrl: "ws://localhost:41551"`, so simply registering it is enough:

```js
import { Editor } from '@f1r3fly-io/lightning-bug';
import { RholangExtension } from '@f1r3fly-io/lightning-bug/extensions';

// Uses the default ws://localhost:41551
// <Editor languages={{ rholang: RholangExtension }} />
```

To target a different host or port, override `lspUrl` (see
[Language Extensions: overriding defaults](./language-extensions.md#overriding-defaults)):

```js
const languages = {
  rholang: { ...RholangExtension, lspUrl: 'ws://my-host:9000' },
};
// <Editor languages={languages} />
```

A language with **no** `lspUrl` never attempts a connection — that is how the default `"text"`
language stays server-free.

## What lights up

Once connected and initialized, and after a document of that language is opened, the server drives
these features:

| Feature | How you observe it |
|---------|--------------------|
| **Diagnostics** | The `diagnostics` event fires; `getDiagnostics(uri?)` returns the current `Diagnostic[]`; errors/warnings render as underlines in the editor (`.cm-error-underline`, `.cm-warning-underline`, … — see [Styling](./styling.md)). |
| **Symbols** | The `symbols` event fires; `getSymbols(uri?)` returns the current `Symbol[]` (with parent links for nesting). |
| **Server logs** | The `log` event carries `{ message, lang }`. |
| **Connection lifecycle** | The `connect`, `lsp-initialized`, `disconnect`, and `lsp-error` events mark transport and handshake state. |

`Diagnostic` and `Symbol` records use **0-based** line/character positions (mirroring the LSP wire
format). A `Diagnostic`'s `severity` is `1` = Error, `2` = Warning, `3` = Info, `4` = Hint. See the
[event catalog](./react-integration.md#the-event-catalog-editorevent) for exact data shapes and
[Querying DataScript](./querying-datascript.md) for reading the same data out of the editor's
database.

## The connection lifecycle

Opening a document of an LSP-enabled language connects the WebSocket (if not already connected),
performs the `initialize` / `initialized` handshake, sends `textDocument/didOpen`, and requests
symbols. Edits are coalesced and sent as `textDocument/didChange` with a monotonically increasing
version. `shutdownLsp()` closes connections cleanly. Green = the language server, slate = the
Workspace that owns the connection, amber = the RxJS event stream, orange = DataScript (where
diagnostics and symbols are stored).

![Sequence diagram: the editor opens a document; the workspace connects the WebSocket, runs initialize/initialized, and sends didOpen, emitting connect and lsp-initialized; the server pushes publishDiagnostics and a symbol response which the workspace stores in DataScript and re-emits as diagnostics and symbols events; user edits send didChange; shutdownLsp sends shutdown/exit and emits disconnect.](diagrams/lsp-connection.svg)

### One connection per language per Workspace

LSP connection state is owned by the **Workspace**, keyed by language. That has two consequences:

- **Panes that share a Workspace share its LSP connections.** Two split-pane editors over the same
  Rholang file (see
  [split panes](./react-integration.md#multi-editor-and-split-pane-workflows)) do **not** open two
  sockets — they use the one Rholang connection on their shared Workspace, and edits from either pane
  coalesce into a single `didChange` stream with one monotonic version.
- **Distinct Workspaces have distinct connections.** Editors created with separate
  `createWorkspace()` handles each maintain their own per-language sockets.

`shutdownLsp(lang?)` shuts down a single language's connection, or all of them if you omit the
argument. It is a good idea to call it before tearing down a long-lived editor.

## Graceful degradation

If the server is not running, is unreachable, or errors mid-session, the editor keeps working as a
plain (but syntax-highlighted) editor. You will see a `disconnect` or `lsp-error` event rather than a
crash. Because analysis features are additive, you can develop the UI with the server off and turn it
on later without code changes.

## Troubleshooting

| Symptom | Likely cause | Fix |
|---------|--------------|-----|
| No diagnostics ever appear | Server not running, or wrong `lspUrl` | Confirm `rholang-language-server --websocket --port 41551` is up; verify the `lspUrl`. |
| `lsp-error` on connect | Port blocked or protocol mismatch | Ensure `--websocket` (not stdio) and that the port matches `lspUrl`. |
| Diagnostics are stale | Edits not reaching the server | Check for `lsp-message` / `content-change` events; very rapid edits are debounced before `didChange`. |
| Symbols empty but diagnostics work | Server returned no symbols for the document | Confirm the document parsed; check server logs surfaced via the `log` event. |

## See also

- [Language Extensions](./language-extensions.md) — where `lspUrl` lives in a `LanguageConfig`.
- [React Integration](./react-integration.md) — `getDiagnostics`, `getSymbols`, `shutdownLsp`, and
  the event catalog.
- [Querying DataScript](./querying-datascript.md) — reading diagnostics/symbols via Datalog.
- Architecture: [the LSP subsystem](../architecture/lsp-subsystem.md) and the
  [formal LSP lifecycle models](../formal/README.md).
