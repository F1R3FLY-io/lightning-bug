import { expectType } from 'tsd';
import { treeSitterWasmUrl } from 'lightning-bug/tree-sitter';

expectType<string>(treeSitterWasmUrl());
