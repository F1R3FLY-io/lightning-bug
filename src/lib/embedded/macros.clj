(ns lib.embedded.macros
  (:require [clojure.java.io])
  (:import [java.util Base64 Base64$Encoder]
           [java.io FileInputStream ByteArrayOutputStream]))

#_{:splint/disable [lint/prefer-method-values]}
(defn slurp-bytes ^bytes [^String path]
  (with-open [^FileInputStream in (FileInputStream. path)
              ^ByteArrayOutputStream out (ByteArrayOutputStream.)]
    (clojure.java.io/copy in out)
    (.toByteArray out)))

#_{:splint/disable [lint/prefer-method-values]}
(defmacro embed-base64 ^String [^String path]
  (let [^bytes bytes (slurp-bytes path)
        ^Base64$Encoder encoder (Base64/getEncoder)
        ^String base64 (.encodeToString encoder bytes)]
    base64))

(defmacro embed-text ^String [^String path]
  (slurp path))
