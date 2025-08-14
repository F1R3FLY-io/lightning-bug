export interface RholangExtensionConfig {
  grammarWasm: string;
  highlightQueryPath: string;
  indentsQueryPath: string;
  lspUrl: string;
  extensions: string[];
  fileIcon: string;
  fallbackHighlighter: string;
}

export const RholangExtension: RholangExtensionConfig;
