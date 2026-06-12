;; death row: removed in phase 3 (control module)
(ns ujima.runtime.protocol)

(defprotocol UjimaSystem
  "Low-level host/system operations.

  This protocol represents machine-level capabilities that are not specific to
  the graphical desktop session. Implementations may call external commands,
  privileged wrappers, or OS APIs.

  These functions operate on actual system state, not desired Ujima settings."

  (hostname [this]
    "Returns the current system hostname.")

  (hostname! [this hostname]
    "Sets the system hostname.")

  (timezone [this]
    "Returns the current system timezone, for example \"Asia/Jerusalem\".")

  (timezone! [this timezone]
    "Sets the system timezone.")

  (keyboard-layouts [this]
    "Returns the currently configured keyboard layouts.")

  (keyboard-layouts! [this layouts]
    "Sets the configured keyboard layouts.")

  (reboot! [this]
    "Reboots the machine immediately.")

  (shutdown! [this]
    "Shuts down the machine immediately."))


(defprotocol UjimaDesktop
  "Low-level desktop/session operations.

  This protocol represents capabilities of the active graphical user session:
  audio, wallpaper, screen lock state, and Ujima-managed applications.

  These functions operate on actual desktop/session state, not desired Ujima
  settings."

 (volume [this]
    "Returns the current output volume as a number from 0 to 100.")

 (volume! [this value]
    "Sets the output volume. `value` must be a number from 0 to 100.")

  (wallpaper [this]
    "Returns the current desktop wallpaper path or identifier.")

  (wallpaper! [this path]
    "Sets the desktop wallpaper.")

  (screen-locked? [this]
    "Returns true if the desktop session is currently locked by Ujima.")

  (screen-lock! [this]
    "Locks the desktop session.")

  (screen-unlock! [this]
    "Unlocks the desktop session.")

  (app-list [this]
    "Returns the list of Ujima-managed applications known to this runtime.")

  (app-info [this name]
    "Returns runtime information for a Ujima-managed application.")

  (app-start! [this name args]
    "Starts a Ujima-managed application.")

  (app-kill! [this name]
    "Stops a Ujima-managed application."))


(defprotocol UjimaDiscovery
  "Low-level discovery operations.

  This protocol represents local-network and local-content discovery.
  Implementations may use mDNS, filesystem scans, static config, or mock data."

  (discover-peers! [this]
    "Discovers Ujima peers on the local network.

    Returns the peers currently visible to this runtime. This may touch the
    network and may block for a short runtime-defined discovery window.")

  (discover-content! [this]
    "Discovers local/offline content available to this runtime.

    Returns content entries visible to this machine."))


(defprotocol UjimaRuntime
  "Top-level runtime interface.

  This protocol represents target-level runtime wiring: external environment
  checks, persistent Ujima settings storage, and control-token handling.

  The runtime is the full target object that may also implement UjimaSystem,
  UjimaDesktop, and UjimaDiscovery."


  (settings [runtime]
    "Reads persistent EDN desired Ujima settings.

    These settings describe what Ujima wants the machine to look like, not
    necessarily what the machine currently looks like.

    Example:

      {:system
       {:hostname \"ujima-03\"
        :timezone \"Asia/Jerusalem\"
        :keyboard-layouts [\"us\" \"il\"]}

       :desktop
       {:wallpaper \"/opt/ujima/wallpapers/default.jpg\"}}")

  (settings! [runtime settings]
    "Writes persistent EDN Ujima settings.

    This should update Ujima's stored settings. It should not necessarily mutate
    live system state directly. Live system state should be brought into sync by
    the agent settings reconciler.")

  (probe-control-token [this]
    "Returns the current control-token state once.

    Return shape:

      {:present? false}

    or:

      {:present? true
       :type :usb}")

  (watch-control-token! [this]
    "Starts watching for control-token state changes.

    Returns a core.async channel that emits control-token state maps using the
    same shape as `probe-control-token`.

    Channel close means the watcher stopped."))


(defprotocol UjimaTryBoot

  (try-boot! [this]
    "Mark the inactive slot for one-shot boot.

     This should prepare the next boot and reboot.")

  (commit-current-boot! [this]
    "Promote the currently booted slot to the normal/default boot slot.

     Call this after a try-booted system passes health checks.")

  (revert-current-boot! [this]
    "Reject the currently booted try slot and restore the previous/default slot
     as the normal boot target."))
