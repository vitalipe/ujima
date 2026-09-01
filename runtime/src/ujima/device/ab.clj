(ns ujima.device.ab)


(defprotocol UjimaSystemDisk


  (ujima-disk-info [this]
    "Return Ujima system disk information, or nil if this is not a valid Ujima system disk.

     Returns a map like:

     {:device  \"/dev/sda\"
      :type    :ab
      :storage \"/dev/sda10\"
      :logs    \"/dev/sda9\"
      :system-disk-id \"1b0c…\"
      :slots {:a {:boot \"/dev/sda2\"
                  :root \"/dev/sda5\"
                  :config \"/dev/sda7\"
                  :ujima-os {:pack-version 1 :packed-at ... :installed-at ...}}
              :b {:boot \"/dev/sda3\"
                  :root \"/dev/sda6\"
                  :config \"/dev/sda8\"
                  :ujima-os nil}}

      :boot-slot :a
      :try-boot-slot :b}

     :try-boot-slot  -> nil when no trial boot is pending.
     :system-disk-id -> the disk's identity, nil when never stamped; lives on the
                        control partition, so it survives slot installs and board swaps.")


  (write-ujima-layout! [this]
    "Destructively write the Ujima A/B partition layout.

     Returns a COLD task — the caller runs it (task/run!!) or joins it (<join!).")


  (install-into-slot! [this ujima-pack-path slot]
    "Write ujima-pack-path into slot :a or :b.

     This destroys the existing content of that slot.

     Returns a COLD task — the caller runs it (task/run!!) or joins it (<join!).")


  (set-boot-slot! [this slot]
    "Set the normal boot slot.")


  (set-try-boot-slot! [this slot]
    "Set a pending trial boot slot. or `nil` to clear try-boot")


  (system-disk-id! [this]
    "The disk's id, created on first call on a freshly provisioned disk; an
     existing one is never rewritten. Read-only: ujima-disk-info's :system-disk-id."))




(defprotocol UjimaBootRuntime

  "How THIS machine boots — over its own disk."


  (try-boot! [this]
    "Reboot into currently set try-boot slot.")


  (running-slot [this]
    "The slot this machine is RUNNING from — a trial boot is not :boot-slot.")


  (trial-boot? [this]
    "Whether the running slot is one the disk has not committed to."))


;; nil is not a valid system disk, so the info READ answers nil (per its
;; contract); the writes stay loud — no nil implementation
(extend-protocol UjimaSystemDisk
  nil
  (ujima-disk-info [_] nil))
