import { RholangExtension } from "./ext";
import type { LanguageConfig } from "./lib";

// Test that RholangExtension is assignable to LanguageConfig
const _test: LanguageConfig = RholangExtension;

// Additional property checks for completeness
_test.extensions; // string[]
_test.grammarWasm; // string | (() => string) | undefined
_test.highlightsQueryPath; // string | (() => string) | undefined
_test.indentsQueryPath; // string | (() => string) | undefined
_test.lspUrl; // string | undefined
_test.fileIcon; // string | undefined
_test.fallbackHighlighter; // string | undefined
_test.indentSize; // number | undefined
