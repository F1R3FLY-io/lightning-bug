# Lightning Bug — Documentation

Lightning Bug is a modern, extensible code editor for the browser, built in **ClojureScript** on
**CodeMirror 6**, with pluggable **Tree-Sitter** syntax, optional **Language Server Protocol (LSP)**
integration over WebSockets, and an in-memory **DataScript** database for editor state. It ships as
a React component (`<Editor>`) plus language extensions.

This directory is the **living documentation**: it describes the system as it is at the current
version (`0.7.7`). It is organized by concern and cross-linked so you can enter from whichever angle
matches your goal.

> Terminology used throughout: **LSP** = Language Server Protocol; **WASM** = WebAssembly;
> **EAV** = Entity–Attribute–Value (the data-model shape DataScript uses); **FSM** = finite-state
> machine; **RxJS** = Reactive Extensions for JavaScript (the event-stream library). These are
> defined more fully where they are first used in each section.

## Reading paths

- **"I want to embed the editor in my app."** Start with [Getting Started](guide/getting-started.md),
  then [React Integration](guide/react-integration.md) and, if you need language servers,
  [LSP Configuration](guide/lsp-configuration.md).
- **"I want to understand how it works."** Start with the
  [Architecture Overview](architecture/README.md) and follow the subsystem docs it links.
- **"I want to add a language."** Read [Language Extensions](guide/language-extensions.md).
- **"I want to contribute or build it."** Read [Building & Testing](development/building-and-testing.md)
  and [Contributing](development/contributing.md).
- **"I care about correctness / formal methods."** Read the [Formal Verification](formal/README.md)
  overview and its [theory primer](formal/theory.md).
- **"I care about security."** Read the [Security Overview](security/README.md) and
  [Threat Model](security/threat-model.md).
- **"I care about performance."** Read the [Performance Model](benchmarks/performance-model.md) and
  the [Benchmarking methodology](benchmarks/README.md).

## Documentation map

| Section | Contents |
|---------|----------|
| **[Architecture](architecture/README.md)** | The design of the system: [hexagonal ports & adapters](architecture/hexagonal-architecture.md), the [editor component](architecture/editor-component.md), [multi-editor Workspaces](architecture/multi-editor-workspaces.md), the [DataScript data model](architecture/data-model.md), the [LSP subsystem](architecture/lsp-subsystem.md), [syntax & editing](architecture/syntax-and-editing.md), and the [RxJS event model](architecture/event-model.md). |
| **[Usage Guide](guide/README.md)** | [Getting started](guide/getting-started.md), [React integration](guide/react-integration.md), [language extensions](guide/language-extensions.md), [LSP configuration](guide/lsp-configuration.md), [querying DataScript](guide/querying-datascript.md), [styling](guide/styling.md), and [TypeScript bindings](guide/typescript-bindings.md). |
| **[Formal Verification](formal/README.md)** | Why the project ships TLA+/Rocq models and proofs, a [temporal-logic theory primer](formal/theory.md), the [models](formal/models.md), the [proofs](formal/proofs.md), and the [source↔formal alignment gate](formal/verification-gate.md). |
| **[Benchmarks](benchmarks/README.md)** | The statistical [methodology](benchmarks/README.md), the durable [performance model](benchmarks/performance-model.md), the [experiment guide](benchmarks/experiment-guide.md), and the [baseline template](benchmarks/baseline-template.md). |
| **[Development](development/building-and-testing.md)** | [Building & testing](development/building-and-testing.md), [contributing](development/contributing.md), and the [release process](development/release-process.md). |
| **[Security](security/README.md)** | The [security overview / trust boundaries](security/README.md) and the [threat model](security/threat-model.md). |
| **[Archive](archive/README.md)** | Preserved historical ledgers (completed campaigns, dated benchmark experiments) — *how the system got here*, kept verbatim. |

## How this documentation is built

- **Diagrams** are authored as [PlantUML](https://plantuml.com/) `.puml` sources under each section's
  `diagrams/` folder and **pre-rendered to committed `.svg`** (embedded via image links) so they
  display on GitHub. Regenerate them with `npm run docs:diagrams` (see
  [Building & Testing](development/building-and-testing.md#documentation-diagrams)). PlantUML is
  preferred because its output is byte-reproducible and it can typeset LaTeX in labels.
- **Mathematics** is written in MathJax and rendered by GitHub. Inline maths uses a
  backtick-delimited dollar span; display maths uses a fenced block whose info string is `math`.
- **Diagram colour legend** (used consistently across every diagram):

| Colour | Concept |
|--------|---------|
| 🔵 Blue | React shell / host application (`lib.core`, the demo `app`) |
| 🟦 Teal | Editor runtime & commands (`lib.editor.runtime`, `lib.editor.commands`) |
| 🟪 Purple | Tree-Sitter / WebAssembly / syntax (`lib.editor.syntax`) |
| 🟩 Green | LSP subsystem (`lib.lsp.*`) |
| 🟧 Orange | DataScript data model (`lib.db`, repositories) |
| ⬜ Slate | Multi-editor Workspace (`lib.workspace`) and domain ports (`domain.protocols`) |
| 🟨 Amber | RxJS event streams |
| 🟥 Crimson | Formal models / verification |

## Provenance

The design and performance knowledge in these docs was distilled from the project's scientific
ledgers, which are preserved verbatim in [`archive/`](archive/README.md). Where a doc references an
optimization or a design decision by its experiment or campaign name (for example `EXP-007` query
coalescence, or the hexagonal migration), the archive holds the original dated record.
