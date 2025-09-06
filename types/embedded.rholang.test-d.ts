import { expectType } from 'tsd';
import { treeSitterRholangWasmUrl } from 'lightning-bug/extensions/lang/rholang/tree-sitter';

expectType<string>(treeSitterRholangWasmUrl());
