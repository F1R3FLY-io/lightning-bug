(ns app.views.main
  (:require [re-com.core :as rc]
            [app.views.editor :refer [component :as editor-comp]]
            [app.views.explorer :refer [component :as explorer]]
            [app.views.symbols :refer [component :as symbols]]
            [app.views.diagnostics :refer [component :as diags]]
            [app.views.output :refer [component :as output]]))

(defn root-component []
  [rc/h-split :components [[explorer] [rc/v-split :components [[editor-comp] [symbols] [diags]]] [output]]
   :class "container-fluid"])
