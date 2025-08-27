import { expectType, expectAssignable } from 'tsd';
import * as React from 'react';
import { Extension } from '@codemirror/state';
import { Observable } from 'rxjs';
import type { EditorProps, EditorRef, EditorState, LanguageConfig, EditorEvent } from './lib.d.ts';

// Check props interface
expectAssignable<EditorProps>({
  languages: { text: { extensions: ['.txt'] } },
  extraExtensions: [] as Extension[],
  defaultProtocol: "inmemory://"
});

// Check ref methods
const ref = React.createRef<EditorRef>();
if (ref.current) {
  expectType<EditorState>(ref.current.getState());
  expectType<void>(ref.current.setText('updated', 'uri'));
  expectType<Observable<EditorEvent>>(ref.current.getEvents());
  expectType<{ line: number; column: number }>(ref.current.getCursor());
  expectType<void>(ref.current.setCursor({ line: 1, column: 1 }));
  expectType<{ from: { line: number; column: number }; to: { line: number; column: number }; text: string } | null>(ref.current.getSelection());
  expectType<void>(ref.current.setSelection({ line: 1, column: 1 }, { line: 1, column: 5 }));
  expectType<void>(ref.current.openDocument('uri', 'text', 'lang'));
  expectType<void>(ref.current.closeDocument('uri'));
  expectType<void>(ref.current.renameDocument('new-name', 'old-uri'));
  expectType<void>(ref.current.saveDocument('uri'));
  expectType<boolean>(ref.current.isReady());
  expectType<void>(ref.current.highlightRange({ line: 1, column: 1 }, { line: 1, column: 5 }));
  expectType<void>(ref.current.clearHighlight());
  expectType<void>(ref.current.centerOnRange({ line: 2, column: 1 }, { line: 2, column: 10 }));
  expectType<string | null>(ref.current.getText('uri'));
  expectType<void>(ref.current.setText('new text', 'uri'));
  expectType<string | null>(ref.current.getFilePath('uri'));
  expectType<string | null>(ref.current.getFileUri('uri'));
  expectType<void>(ref.current.activateDocument('uri'));
  expectType<any>(ref.current.query('query'));
  expectType<any>(ref.current.query('query', [1, 'param']));
  expectType<any>(ref.current.query('query', []));
  expectType<any>(ref.current.getDb());
  expectType<Array<{message: string; severity: number; startLine: number; startChar: number; endLine: number; endChar: number; version?: number}>>(ref.current.getDiagnostics('uri'));
  expectType<Array<{name: string; kind: number; startLine: number; startChar: number; endLine: number; endChar: number; selectionStartLine: number; selectionStartChar: number; selectionEndLine: number; selectionEndChar: number; parent?: number}>>(ref.current.getSymbols('uri'));
}

// Check state shape
const state: EditorState = {
  workspace: {
    documents: { 'uri': { text: '', language: 'text', version: 0, dirty: false, opened: false } },
    activeUri: null,
  },
  cursor: { line: 1, column: 1 },
  selection: null,
  lsp: { "text": { connection: false, url: null, pending: {}, initialized: false } },
  logs: [],
  languages: { text: { extensions: ['.txt'] } },
  diagnostics: [{
    uri: 'test',
    version: 1,
    message: 'err',
    severity: 1,
    startLine: 0,
    startChar: 0,
    endLine: 0,
    endChar: 5
  }],
  symbols: [{
    uri: 'test',
    name: 'sym',
    kind: 1,
    startLine: 0,
    startChar: 0,
    endLine: 0,
    endChar: 1,
    selectionStartLine: 0,
    selectionStartChar: 0,
    selectionEndLine: 0,
    selectionEndChar: 1,
    parent: 0
  }],
};
expectType<EditorState>(state);

// Error cases (e.g., invalid prop)
// @ts-expect-error
const invalid: EditorProps = { invalidProp: true };
void invalid; // suppress unused

// Explicit tests for LanguageConfig
expectAssignable<LanguageConfig>({
  extensions: ['.rho'],
});

expectAssignable<LanguageConfig>({
  grammarWasm: 'path/to/grammar.wasm',
  highlightQueryPath: 'queries/highlights.scm',
  indentsQueryPath: 'queries/indents.scm',
  lspUrl: 'ws://localhost:1234',
  extensions: ['.rho'],
  fileIcon: 'fas fa-code',
  fallbackHighlighter: 'none',
  indentSize: 2,
});

// Negative test: missing required extensions
// @ts-expect-error
expectAssignable<LanguageConfig>({});
void {}; // suppress unused
