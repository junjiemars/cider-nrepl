(ns cider.nrepl.middleware.refresh
  (:require [cider.nrepl.middleware.stacktrace :refer [analyze-causes]]
            [cider.nrepl.middleware.util.misc :as u]
            [clojure.main :refer [repl-caught]]
            [clojure.tools.namespace.dir :as dir]
            [clojure.tools.namespace.reload :as reload]
            [clojure.tools.namespace.repl :as repl]
            [clojure.tools.namespace.track :as track]
            [clojure.tools.nrepl.middleware.interruptible-eval :refer [*msg*]]
            [clojure.tools.nrepl.middleware :refer [set-descriptor!]]
            [clojure.tools.nrepl.misc :refer [response-for]]
            [clojure.tools.nrepl.transport :as transport]))

(defonce ^:private refresh-tracker (agent (track/tracker)))

(defn- scan
  [tracker scan-fn dirs]
  (apply scan-fn tracker (or (seq dirs) [])))

(defn- remove-disabled
  [tracker]
  (#'repl/remove-disabled tracker))

(defn- resolve-and-invoke
  [sym {:keys [session] :as msg}]
  (let [var (some-> sym u/as-sym resolve)]

    (when (or (nil? var)
              (not (var? var)))
      (throw (IllegalArgumentException.
              (format "%s is not resolvable as a var" sym))))

    (when (not (and (fn? @var)
                    (-> (set (:arglists (meta var)))
                        (contains? []))))
      (throw (IllegalArgumentException.
              (format "%s is not a single-arity fn" sym))))

    (binding [*msg* msg
              *out* (get @session #'*out*)
              *err* (get @session #'*err*)]
      (@var))))

(defn- reloading-reply
  [{reloading ::track/load}
   {:keys [transport] :as msg}]
  (transport/send
   transport
   (response-for msg {:reloading reloading})))

(defn- error-reply
  [{:keys [error error-ns]}
   {:keys [print-length print-level session transport] :as msg}]

  (transport/send
   transport
   (response-for msg (cond-> {:status :error}
                       error (assoc :error (analyze-causes error print-length print-level))
                       error-ns (assoc :error-ns error-ns))))

  (binding [*msg* msg
            *err* (get @session #'*err*)]
    (repl-caught error)))

(defn- result-reply
  [{error ::reload/error
    error-ns ::reload/error-ns}
   {:keys [transport] :as msg}]

  (if error
    (error-reply {:error error :error-ns error-ns} msg)
    (transport/send
     transport
     (response-for msg {:status :ok}))))

(defn- before-reply
  [{:keys [before transport] :as msg}]
  (when before
    (transport/send
     transport
     (response-for msg {:status :invoking-before
                        :before before}))

    (resolve-and-invoke before msg)

    (transport/send
     transport
     (response-for msg {:status :invoked-before
                        :before before}))))

(defn- after-reply
  [{error ::reload/error}
   {:keys [after transport] :as msg}]

  (when (and (not error) after)
    (try
      (transport/send
       transport
       (response-for msg {:status :invoking-after
                          :after after}))

      (resolve-and-invoke after msg)

      (transport/send
       transport
       (response-for msg {:status :invoked-after
                          :after after}))

      (catch Exception e
        (error-reply {:error e} msg))))

  (transport/send
   transport
   (response-for msg {:status :done})))

(defn- refresh-reply
  [{:keys [dirs scan-fn] :as msg}]
  (send-off refresh-tracker
            (fn [tracker]
              (try
                (before-reply msg)

                (-> tracker
                    (scan scan-fn dirs)
                    (remove-disabled)
                    (doto (reloading-reply msg))
                    (reload/track-reload)
                    (doto (result-reply msg))
                    (doto (after-reply msg)))

                (catch Throwable e
                  (error-reply {:error e} msg)
                  tracker)))))

(defn wrap-refresh
  "Middleware that provides code reloading."
  [handler]
  (fn [{:keys [op] :as msg}]
    (case op
      "refresh" (refresh-reply (assoc msg :scan-fn dir/scan))
      "refresh-all" (refresh-reply (assoc msg :scan-fn dir/scan-all))
      (handler msg))))

(set-descriptor!
 #'wrap-refresh
 {:requires #{"clone"}
  :handles
  {"refresh"
   {:doc "Reloads all changed files in dependency order."
    :optional {"dirs" "List of directories to scan. If no directories given, defaults to all directories on the classpath."
               "before" "The namespace-qualified name of a zero-arity function to call before reloading."
               "after" "The namespace-qualified name of a zero-arity function to call after reloading."
               "print-length" "Value to bind to `*print-length*` when pretty-printing error data, if an exception is thrown."
               "print-level" "Value to bind to `*print-level*` when pretty-printing error data, if an exception is thrown."}
    :returns {"reloading" "List of namespaces that will be reloaded."
              "status" "`:ok` if reloading was successful; otherwise `:error`."
              "error" "A sequence of all causes of the thrown exception when `status` is `:error`."
              "error-ns" "The namespace that caused reloading to fail when `status` is `:error`."}}
   "refresh-all"
   {:doc "Reloads all files in dependency order."
    :optional {"dirs" "List of directories to scan. If no directories given, defaults to all directories on the classpath."
               "before" "The namespace-qualified name of a zero-arity function to call before reloading."
               "after" "The namespace-qualified name of a zero-arity function to call after reloading."
               "print-length" "Value to bind to `*print-length*` when pretty-printing error data, if an exception is thrown."
               "print-level" "Value to bind to `*print-level*` when pretty-printing error data, if an exception is thrown."}
    :returns {"reloading" "List of namespaces that will be reloaded."
              "status" "`:ok` if reloading was successful; otherwise `:error`."
              "error" "A sequence of all causes of the thrown exception when `status` is `:error`."
              "error-ns" "The namespace that caused reloading to fail when `status` is `:error`."}}}})
