(ns lib.embedded.macros
  (:import [java.util Base64]
           [java.io FileInputStream ByteArrayOutputStream]))

(defn slurp-bytes [path]
  (with-open [in (FileInputStream. path)
              out (ByteArrayOutputStream.)]
    (clojure.java.io/copy in out)
    (.toByteArray out)))

(defmacro embed-base64 [path]
  (let [bytes (slurp-bytes path)
        base64 (.encodeToString (Base64/getEncoder) bytes)]
    base64))

(defmacro embed-text [path]
  (slurp path))
