(ns tasks
  (:require [babashka.tasks :refer [run shell]]))

(defn publish
  "Build the jar and deploy it to Clojars."
  {:org.babashka/cli {:spec {:bump {:coerce :boolean
                                    :desc "Run bump-release first"}}}}
  [{:keys [bump]}]
  (when bump
    (run 'bump-release))
  ((requiring-resolve 'build/deploy) {})
  ;; bump-release pushes the tag, the commit it points at needs this
  (shell "git push"))
