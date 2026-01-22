(ns lib.perf.stats
  "Statistical analysis module for benchmark significance testing.

   Provides:
   - Welch's t-test (unequal variances)
   - Cohen's d effect size
   - Mann-Whitney U test (non-parametric)
   - Outlier removal via IQR rule
   - Sample size calculation for desired power
   - Confidence interval calculations

   All tests use p < 0.05 as the significance threshold by default.")

;; =============================================================================
;; Constants
;; =============================================================================

(def ^:const DEFAULT-ALPHA 0.05)
(def ^:const DEFAULT-POWER 0.80)
(def ^:const IQR-MULTIPLIER 1.5)

;; Critical values for two-tailed t-test at alpha=0.05
;; Index = degrees of freedom - 1 (for df 1-30, then approximations)
(def ^:private t-critical-values
  [12.706 4.303 3.182 2.776 2.571 2.447 2.365 2.306 2.262 2.228
   2.201 2.179 2.160 2.145 2.131 2.120 2.110 2.101 2.093 2.086
   2.080 2.074 2.069 2.064 2.060 2.056 2.052 2.048 2.045 2.042])

;; Standard normal distribution Z-values for common percentiles
(def ^:private z-values
  {:0.90 1.282
   :0.95 1.645
   :0.975 1.960
   :0.99 2.326
   :0.995 2.576})

;; =============================================================================
;; Basic Statistical Functions
;; =============================================================================

(defn mean
  "Calculates the arithmetic mean of a sequence of numbers."
  [xs]
  (when (seq xs)
    (/ (reduce + xs) (count xs))))

(defn variance
  "Calculates the sample variance (n-1 denominator) of a sequence."
  [xs]
  (when (> (count xs) 1)
    (let [m (mean xs)
          n (count xs)
          sum-sq (reduce + (map #(* (- % m) (- % m)) xs))]
      (/ sum-sq (dec n)))))

(defn std-dev
  "Calculates the sample standard deviation."
  [xs]
  (when-let [v (variance xs)]
    (Math/sqrt v)))

(defn median
  "Calculates the median of a sequence."
  [xs]
  (when (seq xs)
    (let [sorted (sort xs)
          n (count sorted)
          mid (quot n 2)]
      (if (odd? n)
        (nth sorted mid)
        (/ (+ (nth sorted mid) (nth sorted (dec mid))) 2)))))

(defn percentile
  "Calculates the nth percentile of a sorted sequence."
  [sorted-xs n]
  (when (seq sorted-xs)
    (let [c (count sorted-xs)
          index (-> (* n (dec c))
                    (/ 100)
                    Math/round
                    (max 0)
                    (min (dec c)))]
      (nth sorted-xs index))))

(defn quartiles
  "Returns [Q1 Q2 Q3] for a sequence."
  [xs]
  (when (seq xs)
    (let [sorted (sort xs)]
      [(percentile sorted 25)
       (percentile sorted 50)
       (percentile sorted 75)])))

(defn iqr
  "Calculates the interquartile range (Q3 - Q1)."
  [xs]
  (when-let [[q1 _ q3] (quartiles xs)]
    (- q3 q1)))

;; =============================================================================
;; Outlier Detection and Removal
;; =============================================================================

(defn outlier-bounds
  "Returns [lower upper] bounds for outlier detection using 1.5*IQR rule."
  [xs]
  (when-let [[q1 _ q3] (quartiles xs)]
    (let [iqr-val (- q3 q1)
          margin (* IQR-MULTIPLIER iqr-val)]
      [(- q1 margin) (+ q3 margin)])))

(defn remove-outliers
  "Removes outliers from a sequence using the 1.5*IQR rule.
   Returns {:cleaned [...] :removed [...] :bounds [lower upper]}."
  [xs]
  (when (seq xs)
    (if-let [[lower upper] (outlier-bounds xs)]
      (let [cleaned (filter #(and (>= % lower) (<= % upper)) xs)
            removed (filter #(or (< % lower) (> % upper)) xs)]
        {:cleaned (vec cleaned)
         :removed (vec removed)
         :bounds [lower upper]
         :outlier-count (count removed)
         :original-count (count xs)})
      {:cleaned (vec xs)
       :removed []
       :bounds nil
       :outlier-count 0
       :original-count (count xs)})))

;; =============================================================================
;; t-distribution Approximation
;; =============================================================================

(defn t-critical
  "Returns the critical t-value for a two-tailed test at alpha=0.05.
   Uses lookup table for df 1-30, then approximates using normal distribution."
  [df]
  (cond
    (< df 1) nil
    (<= df 30) (nth t-critical-values (dec (int df)))
    (<= df 40) 2.021
    (<= df 60) 2.000
    (<= df 80) 1.990
    (<= df 100) 1.984
    (<= df 120) 1.980
    :else 1.960))

(defn- gamma-sterling
  "Sterling's approximation for gamma function."
  [x]
  (* (Math/sqrt (/ (* 2 Math/PI) x))
     (Math/pow (/ (+ x (/ 1 (* 12 x (- 1 (/ 1 (* 10 x x)))))) Math/E) x)))

(defn- beta-function
  "Beta function approximation using Sterling's gamma."
  [a b]
  (/ (* (gamma-sterling a) (gamma-sterling b))
     (gamma-sterling (+ a b))))

(defn- regularized-incomplete-beta
  "Approximation of regularized incomplete beta function.
   Uses continued fraction expansion for better accuracy."
  [x a b]
  (cond
    (<= x 0) 0.0
    (>= x 1) 1.0
    :else
    (let [max-iter 200
          eps 1e-10
          factor (/ (Math/pow x a) a)
          ;; Simple series approximation
          result (loop [sum 1.0
                        term 1.0
                        n 1]
                   (if (or (>= n max-iter) (< (Math/abs term) eps))
                     sum
                     (let [new-term (* term
                                       (/ (* (- a n -1) (- b n -1) x)
                                          (* n (+ a n))))]
                       (recur (+ sum new-term) new-term (inc n)))))]
      (* factor result))))

(defn t-cdf
  "Cumulative distribution function for t-distribution.
   Returns P(T <= t) for given degrees of freedom."
  [t df]
  (let [x (/ df (+ df (* t t)))]
    (if (pos? t)
      (- 1 (* 0.5 (regularized-incomplete-beta x (/ df 2) 0.5)))
      (* 0.5 (regularized-incomplete-beta x (/ df 2) 0.5)))))

(defn t-p-value
  "Calculates the two-tailed p-value for a t-statistic."
  [t-stat df]
  (let [p-one-tail (- 1 (t-cdf (Math/abs t-stat) df))]
    (* 2 p-one-tail)))

;; =============================================================================
;; Welch's t-test
;; =============================================================================

(defn welch-df
  "Calculates Welch-Satterthwaite degrees of freedom for two samples."
  [n1 s1-sq n2 s2-sq]
  (let [term1 (/ s1-sq n1)
        term2 (/ s2-sq n2)
        numerator (* (+ term1 term2) (+ term1 term2))
        denom1 (/ (* term1 term1) (dec n1))
        denom2 (/ (* term2 term2) (dec n2))]
    (/ numerator (+ denom1 denom2))))

(defn welch-t-test
  "Performs Welch's t-test for two independent samples with unequal variances.

   Returns:
   {:t-statistic   - the t-value
    :df            - Welch-Satterthwaite degrees of freedom
    :p-value       - two-tailed p-value
    :significant?  - true if p < alpha
    :mean1         - mean of sample 1
    :mean2         - mean of sample 2
    :diff          - difference in means (mean1 - mean2)
    :ci-lower      - lower bound of 95% confidence interval for difference
    :ci-upper      - upper bound of 95% confidence interval for difference}"
  ([sample1 sample2]
   (welch-t-test sample1 sample2 DEFAULT-ALPHA))
  ([sample1 sample2 alpha]
   (when (and (> (count sample1) 1) (> (count sample2) 1))
     (let [n1 (count sample1)
           n2 (count sample2)
           m1 (mean sample1)
           m2 (mean sample2)
           v1 (variance sample1)
           v2 (variance sample2)
           se (Math/sqrt (+ (/ v1 n1) (/ v2 n2)))
           t-stat (/ (- m1 m2) se)
           df (welch-df n1 v1 n2 v2)
           p-val (t-p-value t-stat df)
           t-crit (or (t-critical df) 1.96)
           margin (* t-crit se)]
       {:t-statistic t-stat
        :df df
        :p-value p-val
        :significant? (< p-val alpha)
        :mean1 m1
        :mean2 m2
        :diff (- m1 m2)
        :se se
        :ci-lower (- (- m1 m2) margin)
        :ci-upper (+ (- m1 m2) margin)
        :n1 n1
        :n2 n2
        :alpha alpha}))))

;; =============================================================================
;; Effect Size: Cohen's d
;; =============================================================================

(defn pooled-std-dev
  "Calculates pooled standard deviation for two samples."
  [sample1 sample2]
  (let [n1 (count sample1)
        n2 (count sample2)
        v1 (variance sample1)
        v2 (variance sample2)]
    (Math/sqrt (/ (+ (* (dec n1) v1) (* (dec n2) v2))
                  (+ n1 n2 -2)))))

(defn cohens-d
  "Calculates Cohen's d effect size for two samples.

   Interpretation:
   - |d| < 0.2: negligible
   - 0.2 <= |d| < 0.5: small
   - 0.5 <= |d| < 0.8: medium
   - |d| >= 0.8: large

   Returns:
   {:d           - Cohen's d value
    :magnitude   - interpretation string
    :meaningful? - true if |d| >= 0.2}"
  [sample1 sample2]
  (when (and (> (count sample1) 1) (> (count sample2) 1))
    (let [m1 (mean sample1)
          m2 (mean sample2)
          s-pooled (pooled-std-dev sample1 sample2)
          d (if (zero? s-pooled) 0 (/ (- m1 m2) s-pooled))
          abs-d (Math/abs d)]
      {:d d
       :magnitude (cond
                    (< abs-d 0.2) "negligible"
                    (< abs-d 0.5) "small"
                    (< abs-d 0.8) "medium"
                    :else "large")
       :meaningful? (>= abs-d 0.2)})))

;; =============================================================================
;; Mann-Whitney U Test (Non-parametric)
;; =============================================================================

(defn- rank-data
  "Assigns ranks to combined data, handling ties with average rank."
  [sample1 sample2]
  (let [combined (concat (map #(vector % 1) sample1)
                         (map #(vector % 2) sample2))
        sorted (sort-by first combined)
        n (count sorted)
        ;; Assign ranks with tie handling
        ranked (loop [i 0
                      result []]
                 (if (>= i n)
                   result
                   (let [current-val (first (nth sorted i))
                         ;; Find all tied values
                         tie-end (loop [j (inc i)]
                                   (if (and (< j n)
                                            (= (first (nth sorted j)) current-val))
                                     (recur (inc j))
                                     j))
                         tie-count (- tie-end i)
                         avg-rank (/ (+ (* 2 (inc i)) (dec tie-count)) 2)]
                     (recur tie-end
                            (into result (map #(conj (nth sorted (+ i %)) avg-rank)
                                              (range tie-count)))))))]
    ranked))

(defn- sum-ranks-for-group
  "Sums ranks for a specific group (1 or 2)."
  [ranked-data group]
  (reduce + (map last (filter #(= group (second %)) ranked-data))))

(defn mann-whitney-u
  "Performs Mann-Whitney U test (non-parametric alternative to t-test).

   Useful when data may not be normally distributed.

   Returns:
   {:u1          - U statistic for sample 1
    :u2          - U statistic for sample 2
    :u           - minimum U value
    :z           - z-score (normal approximation)
    :p-value     - two-tailed p-value (normal approximation)
    :significant? - true if p < alpha}"
  ([sample1 sample2]
   (mann-whitney-u sample1 sample2 DEFAULT-ALPHA))
  ([sample1 sample2 alpha]
   (when (and (seq sample1) (seq sample2))
     (let [n1 (count sample1)
           n2 (count sample2)
           ranked (rank-data sample1 sample2)
           r1 (sum-ranks-for-group ranked 1)
           u1 (- r1 (/ (* n1 (inc n1)) 2))
           u2 (- (* n1 n2) u1)
           u (min u1 u2)
           ;; Normal approximation (valid for n1, n2 > 10)
           mean-u (/ (* n1 n2) 2)
           std-u (Math/sqrt (/ (* n1 n2 (+ n1 n2 1)) 12))
           z (if (zero? std-u) 0 (/ (- u mean-u) std-u))
           ;; Two-tailed p-value using normal approximation
           ;; Using standard normal CDF approximation
           p-val (let [abs-z (Math/abs z)
                       t (/ 1 (+ 1 (* 0.2316419 abs-z)))
                       d (/ (* 0.3989423 (Math/exp (/ (* (- abs-z) abs-z) 2))) 1)
                       prob (* d t (+ 0.3193815
                                      (* t (+ -0.3565638
                                              (* t (+ 1.781478
                                                      (* t (+ -1.821256
                                                              (* t 1.330274)))))))))]
                   (* 2 prob))]
       {:u1 u1
        :u2 u2
        :u u
        :z z
        :p-value p-val
        :significant? (< p-val alpha)
        :n1 n1
        :n2 n2
        :alpha alpha}))))

;; =============================================================================
;; Sample Size Calculation
;; =============================================================================

(defn required-sample-size
  "Calculates the minimum sample size needed for desired power.

   Parameters:
   - effect-size: expected Cohen's d (default 0.5 for medium effect)
   - alpha: significance level (default 0.05)
   - power: desired statistical power (default 0.80)

   Returns the required sample size per group."
  ([]
   (required-sample-size 0.5 DEFAULT-ALPHA DEFAULT-POWER))
  ([effect-size]
   (required-sample-size effect-size DEFAULT-ALPHA DEFAULT-POWER))
  ([effect-size alpha power]
   (let [z-alpha (:0.975 z-values)  ;; two-tailed
         z-beta (cond
                  (>= power 0.99) (:0.99 z-values)
                  (>= power 0.95) (:0.95 z-values)
                  (>= power 0.90) (:0.90 z-values)
                  :else 0.84)  ;; ~0.80 power
         n (Math/ceil (/ (* 2 (Math/pow (+ z-alpha z-beta) 2))
                         (* effect-size effect-size)))]
     {:sample-size (int n)
      :effect-size effect-size
      :alpha alpha
      :power power
      :explanation (str "To detect an effect size of " effect-size
                        " with " (* 100 power) "% power at alpha=" alpha
                        ", need " (int n) " samples per group.")})))

;; =============================================================================
;; Confidence Intervals
;; =============================================================================

(defn confidence-interval
  "Calculates a confidence interval for the mean.

   Returns:
   {:mean    - sample mean
    :lower   - lower bound
    :upper   - upper bound
    :margin  - margin of error
    :level   - confidence level (e.g., 0.95)}"
  ([xs]
   (confidence-interval xs 0.95))
  ([xs level]
   (when (> (count xs) 1)
     (let [n (count xs)
           m (mean xs)
           s (std-dev xs)
           se (/ s (Math/sqrt n))
           ;; Use z-value for large samples, t for small
           critical (if (> n 30)
                      (get z-values (keyword (str (+ 0.5 (/ level 2)))) 1.96)
                      (t-critical (dec n)))
           margin (* critical se)]
       {:mean m
        :lower (- m margin)
        :upper (+ m margin)
        :margin margin
        :level level
        :n n
        :std-dev s
        :se se}))))

;; =============================================================================
;; Comprehensive Statistical Summary
;; =============================================================================

(defn full-statistics
  "Calculates comprehensive statistics for a sample.

   Returns a map with all common statistical measures."
  [xs]
  (when (seq xs)
    (let [sorted (sort xs)
          n (count xs)
          m (mean xs)
          s (if (> n 1) (std-dev xs) 0)
          [q1 q2 q3] (quartiles xs)
          ci (when (> n 1) (confidence-interval xs))]
      {:count n
       :min (first sorted)
       :max (last sorted)
       :mean m
       :median q2
       :std-dev s
       :variance (if (> n 1) (variance xs) 0)
       :q1 q1
       :q3 q3
       :iqr (- q3 q1)
       :p5 (percentile sorted 5)
       :p10 (percentile sorted 10)
       :p25 q1
       :p50 q2
       :p75 q3
       :p90 (percentile sorted 90)
       :p95 (percentile sorted 95)
       :p99 (percentile sorted 99)
       :ci-lower (:lower ci)
       :ci-upper (:upper ci)
       :se (when (> n 1) (/ s (Math/sqrt n)))})))

;; =============================================================================
;; Benchmark Comparison
;; =============================================================================

(defn compare-benchmarks
  "Compares two benchmark result sets with full statistical analysis.

   Arguments:
   - baseline: sequence of baseline measurements
   - experiment: sequence of experiment measurements
   - options: {:alpha 0.05, :remove-outliers? true}

   Returns a comprehensive comparison report."
  ([baseline experiment]
   (compare-benchmarks baseline experiment {}))
  ([baseline experiment {:keys [alpha remove-outliers?]
                         :or {alpha DEFAULT-ALPHA remove-outliers? true}}]
   (when (and (seq baseline) (seq experiment))
     (let [;; Optionally remove outliers
           b-cleaned (if remove-outliers?
                       (:cleaned (remove-outliers baseline))
                       baseline)
           e-cleaned (if remove-outliers?
                       (:cleaned (remove-outliers experiment))
                       experiment)

           ;; Basic stats
           b-stats (full-statistics b-cleaned)
           e-stats (full-statistics e-cleaned)

           ;; Statistical tests
           t-test (welch-t-test e-cleaned b-cleaned alpha)
           effect (cohens-d e-cleaned b-cleaned)
           u-test (mann-whitney-u e-cleaned b-cleaned alpha)

           ;; Improvement calculation (negative = improvement/faster)
           diff (- (:mean e-stats) (:mean b-stats))
           pct-change (when (pos? (:mean b-stats))
                        (* 100 (/ diff (:mean b-stats))))]

       {:baseline-stats b-stats
        :experiment-stats e-stats
        :difference diff
        :percent-change pct-change
        :improved? (neg? diff)

        :welch-t-test t-test
        :cohens-d effect
        :mann-whitney u-test

        :significant? (:significant? t-test)
        :meaningful-effect? (:meaningful? effect)

        :decision (cond
                    (not (:significant? t-test))
                    :REJECT-NOT-SIGNIFICANT

                    (not (:meaningful? effect))
                    :REJECT-TRIVIAL-EFFECT

                    (pos? diff)
                    :REJECT-REGRESSION

                    :else
                    :ACCEPT)

        :summary (cond
                   (not (:significant? t-test))
                   (str "REJECT: Not statistically significant (p="
                        (.toFixed (:p-value t-test) 4) ")")

                   (not (:meaningful? effect))
                   (str "REJECT: Effect size too small (d="
                        (.toFixed (:d effect) 3) ")")

                   (pos? diff)
                   (str "REJECT: Regression detected ("
                        (.toFixed (Math/abs pct-change) 2) "% slower)")

                   :else
                   (str "ACCEPT: Significant improvement ("
                        (.toFixed (Math/abs pct-change) 2) "% faster, p="
                        (.toFixed (:p-value t-test) 4) ", d="
                        (.toFixed (:d effect) 3) ")"))

        :outliers-removed? remove-outliers?
        :baseline-outliers (when remove-outliers?
                             (:outlier-count (remove-outliers baseline)))
        :experiment-outliers (when remove-outliers?
                               (:outlier-count (remove-outliers experiment)))}))))
