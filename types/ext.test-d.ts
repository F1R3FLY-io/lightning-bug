import { expectType, expectAssignable } from 'tsd';
import type { RholangExtensionConfig } from './ext.d.ts';
import { RholangExtension } from 'lightning-bug/extensions';

expectType<RholangExtensionConfig>({
  grammarWasm: 'path',
  highlightQueryPath: 'query.scm',
  indentsQueryPath: 'indents.scm',
  lspUrl: 'ws://',
  extensions: ['.rho'],
  fileIcon: 'icon',
  fallbackHighlighter: 'none',
});

expectAssignable<RholangExtensionConfig>(RholangExtension);
