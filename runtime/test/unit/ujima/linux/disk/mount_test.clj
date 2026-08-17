(ns ujima.linux.disk.mount-test
  "argv shape only — mocked at the spawn level, no real mount is ever launched."
  (:require [clojure.test     :refer [deftest is testing]]
            [babashka.process :as p]
            [lib.shell        :as shell]
            [ujima.linux.disk.mount :as mount]))


;; `$!` slurps stdout and p/checks the pipeline, so its fake must be a real process — an
;; atom is enough only for the `$?` (result!) path
(defn- recording-spawn [calls* result]
  (fn [_opts argv]
    (swap! calls* conj argv)
    (if result (atom result) (p/process ["true"] {:out :stream}))))


(defn- argv-of [f]
  (let [calls* (atom [])]
    (binding [shell/*spawn* (recording-spawn calls* nil)]
      (f))
    (first @calls*)))


(deftest mount-without-opts-has-no-dangling-flag
  (is (= ["sudo" "-n" "mount" "-t" "ext4" "/dev/loop0" "/mnt/x"]
         (argv-of #(mount/mount! "ext4" "/dev/loop0" "/mnt/x")))
      "the image builder's call is unchanged — nil opts must not emit a bare -o")
  (is (= ["sudo" "-n" "mount" "-t" "ext4" "/dev/loop0" "/mnt/x"]
         (argv-of #(mount/mount! "ext4" "/dev/loop0" "/mnt/x" [])))
      "an empty opts seq is the same as none"))


(deftest mount-with-opts-joins-them-into-one-token
  (is (= ["sudo" "-n" "mount" "-t" "vfat" "-o" "ro,nosuid,nodev,noexec"
          "UUID=6962-5E15" "/ujima/run/storage/6962-5E15"]
         (argv-of #(mount/mount! "vfat" {:UUID "6962-5E15"} "/ujima/run/storage/6962-5E15"
                                 ["ro" "nosuid" "nodev" "noexec"])))
      "a map device lowers to one UUID= token, so we never mount by an unstable /dev path"))


(deftest mount-takes-a-ready-comma-string-too
  (is (= ["sudo" "-n" "mount" "-t" "vfat" "-o" "ro,nosuid" "/dev/sda1" "/mnt/x"]
         (argv-of #(mount/mount! "vfat" "/dev/sda1" "/mnt/x" "ro,nosuid")))
      "mount's own syntax is what a caller reaches for — it must not be split per character"))


(deftest umount-lazy-reports-instead-of-throwing
  (testing "argv"
    (is (= ["sudo" "-n" "umount" "-l" "/mnt/x"]
           (argv-of #(mount/umount-lazy! "/mnt/x")))))

  (testing "a failure is a false, not a throw — clearing an empty slot is the common case"
    (let [calls* (atom [])]
      (binding [shell/*spawn* (recording-spawn calls* {:exit 32 :err "not mounted"})]
        (is (false? (mount/umount-lazy! "/mnt/x")))))))
