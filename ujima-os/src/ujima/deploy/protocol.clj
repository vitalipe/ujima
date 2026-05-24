(ns ujima.deploy.protocol)


(defprotocol UjimaDeployTarget

  (ujima-boot-info [this target-device]
    "Return Ujima A/B boot information for target-device.

     This inspects target-device and returns the discovered slot layout,
     installed Ujima OS versions, the normal boot slot, and any pending
     try-boot slot.

     Returns `nil` when target-device does not contain a valid Ujima A/B
     partition layout.

     Returns a map like:

     {:slots {:a {:boot \"/dev/sda2\"
                  :root \"/dev/sda3\"
                  :ujima-os \"1.0\"}
              :b {:boot \"/dev/sda4\"
                  :root \"/dev/sda5\"
                  :ujima-os nil}}
      :boot :a
      :try-boot :b}

     :try-boot is nil when no trial boot is pending.")


  (install-ujima! [this ujima-pack-path target-device]
    "Install Ujima OS onto target-device from ujima-pack-path.

     This is destructive.

     It creates the A/B partition layout, writes the initial Ujima OS pack into
     the first install slot, and prepares the device to boot Ujima OS.

     This should only be used for fresh installs, image creation, or explicit
     full-device reinstall.

     Returns Ujima.Task")


  (upgrade-ujima! [this ujima-pack-path target-device]
    "Upgrade an existing Ujima OS installation on target-device from
     ujima-pack-path.

     This requires target-device to already have a valid Ujima A/B partition
     layout.

     It writes the Ujima OS pack into the inactive slot and destroys whatever
     content currently exists in that slot.

     Returns Ujima.Task"))