import * as React from 'react';
import { Extension } from '@codemirror/state';
import { Observable } from 'rxjs';

export interface EditorProps {
  languages?: Record<string, LanguageConfig>;
  extraExtensions?: Extension[];
}

export interface LanguageConfig {
  grammarWasm?: string;
  highlightQueryPath?: string;
  indentsQueryPath?: string;
  lspUrl?: string;
  extensions: string[];
  fileIcon?: string;
  fallbackHighlighter?: string;
  indentSize?: number;
}

export interface DocState {
  content: string;
  language: string;
  version: number;
  dirty: boolean;
  opened: boolean;
}

export interface LspState {
  connection: boolean;
  url: string | null;
  pending: Record<number, string>;
  initialized?: boolean;
}

export interface EditorState {
  workspace: {
    documents: Record<string, DocState>;
    activeUri: string | null;
  };
  cursor: { line: number; column: number };
  selection: { from: { line: number; column: number }; to: { line: number; column: number }; text: string } | null;
  lsp: Record<string, LspState>;
  logs: Array<{ message: string; lang: string }>;
  languages: Record<string, LanguageConfig>;
  diagnostics: Array<{
    document: { uri: string; version?: number };
    diagnostic: { message: string; severity: number; startLine: number; startChar: number; endLine: number; endChar: number };
    type: 'diagnostic';
  }>;
  symbols: Array<{
    symbol: {
      name: string;
      kind: number;
      startLine: number;
      startChar: number;
      endLine: number;
      endChar: number;
      selectionStartLine: number;
      selectionStartChar: number;
      selectionEndLine: number;
      selectionEndChar: number;
      parent?: number;
    };
    type: 'symbol';
  }>;
}

export interface EditorRef {
  getState(): EditorState;
  getEvents(): Observable<{ type: string; data: any }>;
  getCursor(): { line: number; column: number };
  setCursor(pos: { line: number; column: number }): void;
  getSelection(): { from: { line: number; column: number }; to: { line: number; column: number }; text: string } | null;
  setSelection(from: { line: number; column: number }, to: { line: number; column: number }): void;
  openDocument(uri: string, content?: string, lang?: string, makeActive?: boolean): void;
  closeDocument(uri?: string): void;
  renameDocument(newName: string, oldUri?: string): void;
  saveDocument(uri?: string): void;
  isReady(): boolean;
  highlightRange(from: { line: number; column: number }, to: { line: number; column: number }): void;
  clearHighlight(): void;
  centerOnRange(from: { line: number; column: number }, to: { line: number; column: number }): void;
  getText(uri?: string): string | null;
  setText(text: string, uri?: string): void;
  getFilePath(uri?: string): string | null;
  getFileUri(uri?: string): string | null;
  setActiveDocument(uri: string): void;
}

export const Editor: React.ForwardRefExoticComponent<EditorProps & React.RefAttributes<EditorRef>>;
