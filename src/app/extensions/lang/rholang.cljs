(ns app.extensions.lang.rholang
  (:require
   [app.languages :as langs]
   [ext.lang.rholang :refer [config]]))

;; Register Rholang language with string key for consistency.
(langs/register-language "rholang" config)
