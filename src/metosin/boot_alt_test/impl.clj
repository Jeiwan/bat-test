(ns metosin.boot-alt-test.impl
  (:require [clojure.tools.namespace.dir :as dir]
            [clojure.tools.namespace.track :as track]
            [clojure.tools.namespace.reload :as reload]
            [clojure.string :as string]
            [eftest.runner :as runner]
            [metosin.boot-alt-test.eftest :as eftest]
            [boot.util :as util]
            [boot.pod :as pod]))

(def tracker (atom nil))
(def running (atom false))

(declare run-all)

(defn on-keypress
  [key f]
  (letfn [(read-in [] (.read System/in))]
    (future
      (loop [k (read-in)]
        (when (= k key) (f k))
        (recur (read-in))))))

(defn enter-key-listener
  [opts]
  (util/dbug "Listening to the enter key\n")
  (on-keypress
   java.awt.event.KeyEvent/VK_ENTER
   (fn [_]
     (when-not @running
       (util/info "Running all tests\n")
       (run-all opts)))))

(defn reload-and-test
  [tracker {:keys [test-matcher parallel? report-sym]
            :or {test-matcher #".*test"}}]
  (let [changed-ns (::track/load @tracker)
        tests (filter #(re-matches test-matcher (name %)) changed-ns)]

    (util/dbug "Unload: %s\n" (pr-str (::track/unload @tracker)))
    (util/dbug "Load: %s\n" (pr-str (::track/load @tracker)))

    (swap! tracker reload/track-reload)

    (try
      (when (::reload/error @tracker)
        (util/fail "Error reloading: %s\n" (name (::reload/error-ns @tracker)))
        (throw (::reload/error @tracker)))
      (catch java.io.FileNotFoundException e
        (util/info "Reseting tracker due to file not found exception, all namespaces will be reloaded next time.\n")
        (reset! tracker (track/tracker))
        (throw e)))

    (util/info "Testing: %s\n" (string/join ", " tests))

    (eftest/run-tests
      (runner/find-tests tests)
      (->> {:multithread? parallel?
            :report (when report-sym
                      (require (symbol (namespace report-sym)))
                      (deref (resolve report-sym)))}
           (remove (comp nil? val))
           (into {})))))

(defn run [opts]
  (try
    (reset! running true)
    (swap! tracker (fn [tracker]
                     (util/dbug "Scan directories: %s\n" (pr-str (:directories pod/env)))
                     (dir/scan-dirs (or tracker (track/tracker)) (:directories pod/env))))

    (reload-and-test tracker opts)
    (finally
      (reset! running false))))

(defn run-all [opts]
  (reset! tracker nil)
  (run opts))
