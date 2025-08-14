import { expectType } from 'tsd';
import type { RholangExtensionConfig } from './ext.d.ts';

expectType<RholangExtensionConfig>({
  grammarWasm: 'path',
  highlightQueryPath: 'query.scm',
  indentsQueryPath: 'indents.scm',
  lspUrl: 'ws://',
  extensions: ['.rho'],
  fileIcon: 'icon',
  fallbackHighlighter: 'none',
});
