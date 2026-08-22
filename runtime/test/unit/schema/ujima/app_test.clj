(ns schema.ujima.app-test
  (:require [clojure.test :refer [deftest is]]
            [malli.core  :as m]
            [malli.error :as me]
            [schema.ujima.app :as app]))


(defn- errors [spec] (me/humanize (m/explain app/spec spec)))


(deftest every-kind-as-authored
  (is (m/validate app/spec {:kind :exec :label "Calc" :category :office :class "libreoffice-calc"
                            :exec ["libreoffice" "--calc"]}))
  (is (m/validate app/spec {:kind :exec :label "Console" :category :system :hidden true :exec ["sh" "app/run.sh"]}))
  (is (m/validate app/spec {:kind :exec :label "Sky" :exec ["stellarium"] :mode :fullscreen}))
  (is (m/validate app/spec {:kind :web-app :label "Board" :category :create :entry "index.html" :port 8090}))
  (is (m/validate app/spec {:kind :link :label "Kolibri" :category :learn :url "http://localhost:8080"})))


(deftest errors-name-the-field
  (is (= {:exec ["missing required key"]} (errors {:kind :exec :label "NoExec"})))
  (is (= {:kind ["invalid dispatch value"]} (errors {:label "NoKind" :exec ["x"]})) ":kind is mandatory")
  (is (= {:kind ["invalid dispatch value"]} (errors {:kind :flatpak :label "M"})))
  (is (= {:url ["must be an http(s) url"]} (errors {:kind :link :label "L" :url "ftp://x"})))
  (is (= {:exec ["should have at least 1 elements"]} (errors {:kind :exec :label "E" :exec []})))
  (is (= {:category ["not a category ujima knows"]} (errors {:kind :exec :label "E" :exec ["x"] :category :games})))
  (is (= ["invalid dispatch value"] (errors nil)) "not even a map"))


(deftest maps-are-closed
  (is (= {:label ["missing required key"] :lable ["disallowed key"]} (errors {:kind :exec :lable "typo" :exec ["x"]}))
      "a typo is an error, not a silently ignored key")
  (is (= {:env ["disallowed key"]} (errors {:kind :exec :label "E" :exec ["x"] :env {"T" "1"}}))
      ":env is the session's, never authored")
  (is (= {:class ["disallowed key"]} (errors {:kind :link :label "L" :url "http://x" :class "mine"}))
      "the web kinds derive :class from the id"))
