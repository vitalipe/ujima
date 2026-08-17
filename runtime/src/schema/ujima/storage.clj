(ns schema.ujima.storage
  "What a mounted partition is scanned for: filename -> what a hit means. Storage reports
   hits and never interprets them.")


(def markers {".ujima-admin-token" :circle/secret})
