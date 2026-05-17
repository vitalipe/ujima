(ns ujima.deploy.protocol)


(defprotocol UjimaDeployTarget

  (create-ab-partition-layout! [this target-device]
    "Create the A/B partition layout for this runtime.

     This is destructive and should only be used during image creation,
     device installation, or explicit repartitioning.")


  (write-ujima! [this ujima-pack-path target-device]
    "Write a Ujima OS pack from ujima-pack-path into the inactive slot.

     On a fresh A/B layout with no valid installed slot, the inactive slot is the
     first install slot.

     This destroys whatever content currently exists in the target slot.

     Returns a core.async channel of progress events."))


