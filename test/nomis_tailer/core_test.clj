(ns nomis-tailer.core-test
  (:require [clojure.core.async :as a]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [midje.sweet :refer :all]
            [nomis-tailer.core :as subject])
  (:import (java.io File)))

(defn do-pretend-logging-with-file-replacement
  "Send lines to `f` in a manner that is similar to the way logging happens."
  ;; When making changes here, think about `file-replacement-freq-ms` and how it relates
  ;; to the underlying TailerListener's delay-ms.
  [f lines-s file-replacement-freq-ms]
  ;; (println "do-pretend-logging-with-file-replacement" (.getName f))
  (io/make-parents f)
  (doseq [lines lines-s]
    (spit f "") ; replace
    (doseq [line lines]
      (spit f
            (str line "\n")
            :append true))
    (Thread/sleep file-replacement-freq-ms)))

(defn chan->seq [c]
  (lazy-seq
   (when-let [v (a/<!! c)]
     (cons v (chan->seq c)))))

(fact "`make-single-file-tailer` works"
  (let [file-replacement-freq-ms 1000
        delay-ms                 50
        lines-s                  [["1-1" "2-1" "3-1" "4-1" "5-1"]
                                  ["1-2" "2-2" "3-2" "4-2" "5-2"]
                                  ["1-3" "2-3" "3-3" "4-3" "5-3"]]
        file                     (File. "test/_work-dir/single-filename-test.log")
        tailer                   (subject/make-single-file-tailer file
                                                                  delay-ms)
        result-ch                (a/thread (doall (-> tailer
                                                      subject/channel
                                                      chan->seq)))]
    (Thread/sleep 100) ; avoid writing before we are ready to read
    (do-pretend-logging-with-file-replacement file lines-s file-replacement-freq-ms)
    (subject/close! tailer)
    (a/<!! result-ch))
  => ["1-1" "2-1" "3-1" "4-1" "5-1"
      "1-2" "2-2" "3-2" "4-2" "5-2"
      "1-3" "2-3" "3-3" "4-3" "5-3"])

(fact "`make-multi-file-tailer` works"
  (let [file-replacement-freq-ms    1000
        delay-ms                    50
        new-file-check-frequency-ms 300
        delay-ms-to-finish-old-file 300
        basic-lines-s               [["1-1" "2-1" "3-1" "4-1" "5-1"]
                                     ["1-2" "2-2" "3-2" "4-2" "5-2"]
                                     ["1-3" "2-3" "3-3" "4-3" "5-3"]]
        modify-lines-s              (fn [prefix]
                                      (map (fn [lines]
                                             (map #(str prefix %)
                                                  lines))
                                           basic-lines-s))
        dir                         (File. "test/_work-dir")
        pattern                     #"multi-filename-test-.\.log"
        tailer                      (subject/make-multi-file-tailer
                                     dir
                                     pattern
                                     delay-ms
                                     new-file-check-frequency-ms
                                     delay-ms-to-finish-old-file)
        result-ch                   (a/thread (doall (-> tailer
                                                         subject/channel
                                                         chan->seq)))]
    (Thread/sleep 100) ; avoid writing before we are ready to read
    (doseq [i ["a" "b" "c"]]
      (let [file (File. (str "test/_work-dir/multi-filename-test-" i ".log"))]
        (do-pretend-logging-with-file-replacement file
                                                  (modify-lines-s (str i "-"))
                                                  file-replacement-freq-ms)))
    (subject/close! tailer)
    (a/<!! result-ch))
  => ["a-1-1" "a-2-1" "a-3-1" "a-4-1" "a-5-1"
      "a-1-2" "a-2-2" "a-3-2" "a-4-2" "a-5-2"
      "a-1-3" "a-2-3" "a-3-3" "a-4-3" "a-5-3"
      "b-1-1" "b-2-1" "b-3-1" "b-4-1" "b-5-1"
      "b-1-2" "b-2-2" "b-3-2" "b-4-2" "b-5-2"
      "b-1-3" "b-2-3" "b-3-3" "b-4-3" "b-5-3"
      "c-1-1" "c-2-1" "c-3-1" "c-4-1" "c-5-1"
      "c-1-2" "c-2-2" "c-3-2" "c-4-2" "c-5-2"
      "c-1-3" "c-2-3" "c-3-3" "c-4-3" "c-5-3"])
