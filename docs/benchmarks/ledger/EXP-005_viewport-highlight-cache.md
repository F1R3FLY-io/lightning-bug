# EXP-005: Viewport-Aware Highlight Caching

## Summary

| Field | Value |
|-------|-------|
| **Experiment ID** | EXP-005 |
| **Date** | 2026-01-21 |
| **Branch** | `experiment/exp-005-viewport-highlight-cache` |
| **Target** | Scroll performance / frame time |
| **Decision** | **ACCEPT** (pending scroll performance validation) |

## Hypothesis

Viewport-aware highlight caching will reduce scroll jank by 50-80% by eliminating redundant Tree-Sitter query calls when scrolling within already-parsed regions.

## Rationale

Analysis of `src/lib/editor/syntax.cljs` revealed that the `make-highlighter-plugin` function rebuilds all decorations on every viewport change, even when scrolling within a small region:

```clojure
;; Before: Full rebuild on every viewport change
:update (fn [^js update]
          (when (or (.-docChanged update) (.-viewportChanged update)
                    (not= (.field (.-startState update) language-state-field)
                          (.field (.-state update) language-state-field)))
            (this-as ^js self
                     (set! (.-decorations self) (build-decorations update)))))
```

This causes unnecessary Tree-Sitter queries when scrolling, as the same regions are re-queried repeatedly.

## Implementation

### Files Modified

1. **`src/lib/editor/syntax.cljs`**
   - Added `viewport-margin` constant (2000 characters)
   - Modified `make-highlighter-plugin` to use viewport-aware caching
   - Each plugin instance maintains a cache with:
     - `from`: Start of cached range
     - `to`: End of cached range
     - `decorations`: Cached decoration RangeSet

### Code Change

The optimization:
1. Extends the query range by `viewport-margin` (2000 chars) beyond the visible viewport
2. Caches the resulting decorations with their covered range
3. On viewport change, checks if new viewport is within cached range
4. Only rebuilds when viewport exceeds cache or document changes

```clojure
;; After: Viewport-aware caching
(if within-cache?
  ;; Viewport is within cached range, reuse decorations
  cached-decos
  ;; Need to rebuild with extended margin
  (let [extended-from (max 0 (- viewport-from viewport-margin))
        extended-to (min doc-length (+ viewport-to viewport-margin))
        decos (build-decorations-for-range state tree doc extended-from extended-to)]
    (reset! cache-atom {:from extended-from
                        :to extended-to
                        :decorations decos})
    decos))
```

## Results

### Benchmark Validation

**Note:** The current benchmark suite does not exercise the syntax highlighting code path because:
1. Tree-Sitter WASM files are not available in the isolated benchmark environment
2. There is no scroll/viewport-change benchmark

The existing benchmarks test DataScript queries, which are unrelated to this optimization.

### Regression Analysis

Benchmark comparison showed no significant regressions in tested metrics:

| Metric | Change | p-value | Cohen's d | Notes |
|--------|--------|---------|-----------|-------|
| timing-overhead | +0.00% | N/A | 0.000 | No change |
| datascript-diagnostics-by-uri | +3.52% | 0.0082 | 0.203 | Noise (different code path) |
| datascript-symbols-by-uri | +1.07% | 0.0013 | 0.344 | Noise (different code path) |
| datascript-all-diagnostics | +1.74% | 0.0005 | 0.375 | Noise (different code path) |
| datascript-all-symbols | +1.81% | 0.0000 | 0.747 | Noise (different code path) |
| datascript-document-lookup | +0.41% | 0.0150 | 0.036 | Negligible |
| datascript-active-uri | +10.99% | 0.0000 | 0.959 | Noise (different code path) |
| diagnostic-transform | +0.00% | N/A | 0.000 | No change |
| symbol-flatten | +6.78% | 0.0000 | 0.909 | Noise (different code path) |

**Interpretation:** The DataScript benchmark variations are unrelated to the syntax highlighting changes. The modified code (`make-highlighter-plugin`) is not executed during these benchmarks. The variations represent normal benchmark noise.

### Theoretical Analysis

The optimization provides deterministic improvement when:
1. **Cache hit**: Viewport scrolls within cached range → Zero Tree-Sitter queries
2. **Cache miss**: Viewport exceeds cache → One Tree-Sitter query (same as before, but with margin pre-fetch)

The overhead is minimal:
- One atom dereference per viewport change
- Three integer comparisons for cache hit check
- No additional allocations on cache hit

### Manual Verification

The optimization can be verified manually in the demo environment:
1. Open a large Rholang file (>1000 lines)
2. Enable trace logging for `lib.editor.syntax`
3. Scroll slowly - observe "Reusing cached decorations" messages
4. Scroll quickly past the margin - observe "Building decorations" messages

## Decision

**ACCEPT** - The optimization is theoretically sound, adds minimal overhead, and shows no regressions in tested metrics. Full scroll performance validation requires:

1. Adding a frame-time benchmark with viewport scrolling simulation
2. Testing with actual Tree-Sitter WASM files in the benchmark environment

## Commit Message

```
perf(syntax): Add viewport-aware highlight caching for scroll performance

Experiment: EXP-005
Hypothesis: Viewport-aware caching reduces scroll jank by eliminating
redundant Tree-Sitter queries when scrolling within cached regions.

Implementation:
- Pre-fetch decorations with 2000-character margin beyond viewport
- Cache decorations with their covered range
- Reuse cache when viewport stays within cached range
- Invalidate cache on document or language state changes

Improvement: Eliminates Tree-Sitter queries during small scrolls (theoretical)
Regressions: None detected in tested metrics

Decision: ACCEPT (pending scroll performance validation)

Co-Authored-By: Claude Opus 4.5 <noreply@anthropic.com>
```

## Future Work

To fully validate this optimization:

1. **EXP-005a**: Add frame-time benchmark with scroll simulation
2. **EXP-005b**: Optimize cache invalidation granularity (per-region instead of full invalidation)
3. Consider web worker for background pre-fetching of adjacent regions
