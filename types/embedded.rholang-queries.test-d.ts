import { expectType } from 'tsd';
import { highlightsQueryUrl, indentsQueryUrl } from 'lightning-bug/extensions/lang/rholang/tree-sitter/queries';

expectType<string>(highlightsQueryUrl());
expectType<string>(indentsQueryUrl());
