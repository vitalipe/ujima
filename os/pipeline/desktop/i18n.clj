(ns pipeline.desktop.i18n
  "Writes the GNU gettext .mo binary format directly from a catalog map — the ~40 lines
   that replace a vendored binary + python producer. GTK's chooser-label override is
   generated from the desktop stage's catalog at stage time in the chroot; no gettext
   toolchain involved. Format: little-endian header (magic, revision, count, key-table
   offset, value-table offset, hash size/offset), sorted-msgid key/value tables of
   (length, offset) pairs, then the NUL-terminated strings (lengths exclude the NUL)."
  (:import [java.nio ByteBuffer ByteOrder]))


(defn mo-bytes ^bytes [catalog]
  (let [entries    (sort-by key catalog)          ;; .mo requires msgids sorted ("" first)
        utf8       (fn [^String s] (.getBytes s "UTF-8"))
        ids        (mapv (comp utf8 key) entries)
        vals       (mapv (comp utf8 val) entries)
        n          (count entries)
        sizes      (fn [bs] (reduce + (map #(inc (alength ^bytes %)) bs)))
        keystart   (+ 28 (* 16 n))
        valuestart (+ keystart (sizes ids))
        buf        (doto (ByteBuffer/allocate (+ valuestart (sizes vals)))
                     (.order ByteOrder/LITTLE_ENDIAN))
        table!     (fn [bs start]
                     (reduce (fn [off ^bytes b]
                               (.putInt buf (alength b))
                               (.putInt buf (+ start off))
                               (+ off (alength b) 1))
                             0 bs))
        strings!   (fn [bs] (doseq [^bytes b bs] (.put buf b) (.put buf (byte 0))))]
    (.putInt buf (unchecked-int 0x950412DE))      ;; magic
    (.putInt buf 0)                               ;; format revision
    (.putInt buf n)
    (.putInt buf 28)                              ;; key table right after the header
    (.putInt buf (+ 28 (* 8 n)))                  ;; value table after the key table
    (.putInt buf 0)                               ;; no hash table
    (.putInt buf 0)
    (table! ids keystart)
    (table! vals valuestart)
    (strings! ids)
    (strings! vals)
    (.array buf)))
