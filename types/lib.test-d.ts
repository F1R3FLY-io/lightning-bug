import { expectType, expectAssignable, expectError } from 'tsd';
import * as React from 'react';
import { Extension } from '@codemirror/state';
import { Observable } from 'rxjs';
import type { EditorProps, EditorRef, EditorState, LanguageConfig } from './lib.d.ts';

// Check props interface
expectAssignable<EditorProps>({
  content: 'test',
  language: 'rholang',
  languages: { rholang: { extensions: ['.rho'] } },
  onContentChange: (content: string) => {},
  extraExtensions: [] as Extension[],
});

// Check ref methods
const ref = React.createRef<EditorRef>();
if (ref.current) {
  expectType<EditorState>(ref.current.getState());
  expectType<void>(ref.current.setContent('new content'));
  expectType<Observable<{ type: string; data: any }>>(ref.current.getEvents());
  expectType<{ line: number; column: number }>(ref.current.getCursor());
  expectType<void>(ref.current.setCursor({ line: 1, column: 1 }));
  expectType<{ from: { line: number; column: number }; to: { line: number; column: number }; text: string } | null>(ref.current.getSelection());
  expectType<void>(ref.current.setSelection({ line: 1, column: 1 }, { line: 1, column: 5 }));
  expectType<void>(ref.current.openDocument('uri', 'content', 'lang'));
  expectType<void>(ref.current.closeDocument());
  expectType<void>(ref.current.renameDocument('new-name'));
  expectType<void>(ref.current.saveDocument());
  expectType<boolean>(ref.current.isReady());
  expectType<void>(ref.current.highlightRange({ line: 1, column: 1 }, { line: 1, column: 5 }));
  expectType<void>(ref.current.clearHighlight());
  expectType<void>(ref.current.centerOnRange({ line: 1, column: 1 }, { line: 1, column: 5 }));
  expectType<void>(ref.current.setText('text'));
}

// Check state shape
const state: EditorState = {
  uri: null,
  content: '',
  language: 'text',
  version: 0,
  dirty: false,
  opened: false,
  cursor: { line: 1, column: 1 },
  selection: null,
  lsp: { connection: false, url: null, pending: {}, initialized: false, logs: [] },
  languages: { text: { extensions: ['.txt'] } },
  diagnostics: [],
  symbols: [],
};
expectType<EditorState>(state);

// Error cases (e.g., invalid prop)
// @ts-expect-error
const invalid: EditorProps = { invalidProp: true };
