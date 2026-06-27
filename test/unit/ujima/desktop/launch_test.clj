(ns ujima.desktop.launch-test
  (:require [clojure.test :refer [deftest is]]
            [ujima.desktop.launch :as launch]))


(def ctx {:chromium "chromium" :profile-dir "/run/ujima/desktop/chrome"})


(deftest window-class-stamps-web-and-keeps-natural-class
  (is (= "ujima-wikipedia" (launch/window-class {:id :wikipedia :kind :web})))
  (is (= "libreoffice-writer"
         (launch/window-class {:id :write :kind :desktop :wm-class "libreoffice-writer"}))))


(deftest web-argv-builds-app-window-with-shared-ephemeral-profile
  (is (= ["chromium"
          "--app=http://ujima-content.local/wiki"
          "--class=ujima-wikipedia"
          "--user-data-dir=/run/ujima/desktop/chrome"
          "--disk-cache-size=1"
          "--no-first-run"
          "--no-default-browser-check"]
         (launch/web-argv {:id :wikipedia :kind :web :url "http://ujima-content.local/wiki"}
                          ctx))))


(deftest desktop-argv-is-exec-verbatim
  (is (= ["libreoffice" "--writer"]
         (launch/desktop-argv {:id :write :kind :desktop :exec ["libreoffice" "--writer"]} ctx))))


(deftest launch-argv-dispatches-by-kind-and-rejects-shell
  (is (= "--app=http://x/wiki"
         (second (launch/launch-argv {:id :w :kind :web :url "http://x/wiki"} ctx))))
  (is (= ["tuxpaint"]
         (launch/launch-argv {:id :draw :kind :desktop :exec ["tuxpaint"]} ctx)))
  (is (thrown? Exception (launch/launch-argv {:id :launcher :kind :shell} ctx))))
