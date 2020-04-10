(ns lang.type-checker
  (:refer-clojure :exclude [apply drop type])
  (:require [clojure.core.match :refer [match]]
            clojure.reflect
            [clojure.string :as str]
            [clojure.walk :as walk]
            [lang.interpreter :as interpreter]
            [lang.jvm :as jvm]
            [lang.module :as module]
            [lang.type :as type]
            [lang.utils :as utils :refer [undefined]]
            [lang.zip :as zip])
  (:import java.lang.Class))

(declare analysis:check)
(declare match:check)
(declare synthesize)
(declare subtyping:join)
(declare subtyping:polarity)
(declare subtype)

(defn- next-variable-id
  [module]
  (swap! (:type-checker/current-variable module) inc))

(defn- fresh-existential
  ([module]
   (fresh-existential module :kind/type))
  ([module kind]
   (let [variable-id (next-variable-id module)
         type    {:ast/type :existential-variable
                  :id       variable-id}]
     (swap! (:type-checker/facts module)
       zip/insert-left
       {:fact/declare-existential variable-id
        :kind                     kind})
     type)))

(defn- fresh-universal
  ([module]
   (fresh-universal module :kind/type))
  ([module kind]
   (let [variable-id (next-variable-id module)
         type        {:ast/type :universal-variable
                      :id       variable-id}]
     (swap! (:type-checker/facts module)
       zip/insert-left
       {:fact/declare-universal variable-id
        :kind                   kind})
     type)))

(defn- fresh-mark
  [module]
  (let [marker {:fact/marker (next-variable-id module)}]
    (swap! (:type-checker/facts module) zip/insert-left marker)
    marker))

(defn- drop
  [module fact]
  (let [zipper
        (swap! (:type-checker/facts module)
          #(-> %
             (zip/->end)
             (zip/focus-left fact)
             (zip/left)))
        [remainder discard] (zip/split zipper)]
    (reset! (:type-checker/facts module) remainder)
    discard))

(defn- bind-symbol
  [module symbol type principality]
  (swap! (:type-checker/facts module)
    zip/insert-left
    {:fact/bind-symbol (select-keys symbol [:reference :name :in])
     :type             type
     :principality     principality})
  nil)

(defn- existential-solutions
  [module]
  (->> module
    :type-checker/facts
    (deref)
    (zip/left-seq)
    (keep (fn [fact]
            (when-let [existential (:fact/solve-existential fact)]
              [existential (:type fact)])))
    (into {})))

(defn- local-bindings
  [module]
  (->> module
    :type-checker/facts
    (deref)
    (zip/left-seq)
    (keep (fn [fact]
            (when-let [symbol (:fact/bind-symbol fact)]
              [symbol {:type ((juxt :type :principality) fact)}])))
    (into {})))

(defn- all-bindings
  [module]
  (merge
    (module/all-bindings module)
    (local-bindings module)))

(defn- lookup-binding
  [module symbol]
  (or
    (:type (get (all-bindings module) (select-keys symbol [:reference :name :in])))
    (throw (ex-info "Unknown binding"
             {:symbol symbol
              :module (:name module)}))))

(defn- lookup-type
  [module name]
  (or
    (:body (get (module/all-types module) name))
    (throw (ex-info "Unknown type"
             {:type name
              :module (:name module)}))))

(defn- lookup-macro
  [module name]
  (get (module/all-macros module) name))

(defn- apply
  [module type]
  (match type
    {:ast/type :existential-variable :id id}
    (or
      (some->> id
        (get (existential-solutions module))
        (apply module))
      type)

    {:ast/type :universal-variable :id id}
    type

    {:ast/type :forall}
    (update type :body (partial apply module))

    {:ast/type :function}
    (-> type
      (update :domain (partial apply module))
      (update :return (partial apply module)))

    {:ast/type :variant}
    (update type :injectors
      #(->> %
         (map (fn [[injector value]] [injector (some->> value (apply module))]))
         (into {})))

    {:ast/type :vector}
    (update type :inner (partial apply module))

    {:ast/type :primitive}
    type

    {:ast/type :named :name name}
    (or
      (lookup-type module name)
      (undefined :apply/named.unknown))

    {:ast/type :object}
    type

    {:ast/type :quote}
    (update type :inner (partial apply module))

    _
    (undefined :apply/fallthrough)))

(defn- solve-existential
  ([module existential solution]
   (solve-existential module existential solution :kind/type))
  ([module existential solution kind]
   (let [existing-solution {:fact/solve-existential existential
                            :kind                   kind
                            :type                   (get (existential-solutions module) existential)}
         new-solution      {:fact/solve-existential existential
                            :kind                   kind
                            :type                   (apply module solution)}]
     (swap! (:type-checker/facts module)
       #(walk/prewalk-replace ; FIXME
          {{:fact/declare-existential existential :kind kind} new-solution
           existing-solution                                  new-solution}
          %)))
   nil))

(defn- instantiate-universal
  ([module name type]
   (instantiate-universal module name type true))
  ([module name type annotate?]
   (-> type
     (match
       {:ast/type :forall :variable universal-variable :body body}
       (let [existential-variable (fresh-existential module)
             instance-type (->> (instantiate-universal module name body false)
                             (first)
                             (walk/prewalk-replace {universal-variable existential-variable}))]
         [(cond-> instance-type annotate? (assoc :type-checker/instance-of name))
          :non-principal])

       _
       [type :principal]))))

(defn- setup-annotations
  [definition]
  (walk/prewalk
    (fn [node]
      (cond-> node
        (:ast/term node)
        (assoc :type-checker/type (promise))

        (= :application (:ast/term node))
        (assoc :macro/expands-to (promise))))
    definition))

(defn- resolve-annotations
  [definition]
  (walk/prewalk
    (fn resolve-node [node]
      (cond
        (some-> node :macro/expands-to (realized?))
        (recur (-> node
                 :macro/expands-to
                 (deref)
                 (assoc :macro/expanded-from
                   (dissoc node :macro/expands-to :type-checker/type))))

        (some-> node :type-checker/type (realized?))
        (update node :type-checker/type deref)

        (:type-checker/type node)
        (dissoc node :type-checker/type)

        :else node))
    definition))

(defn- annotate-type
  [term type]
  (when-let [promise (:type-checker/type term)]
    (deliver promise type)))

(defn- generalize
  [module mark type]
  (let [discard (drop module mark)
        universal-variables
        (reduce
          (fn [universal-variables fact]
            (match fact
              {:fact/declare-existential existential-variable-id :kind kind}
              (let [existential-variable  {:ast/type :existential-variable
                                           :id       existential-variable-id}
                    universal-variable    (fresh-universal module)]
                (swap! (:type-checker/facts module)
                  #(-> %
                     (zip/insert-right {:fact/solve-existential existential-variable-id
                                        :kind kind
                                        :type universal-variable})
                     (zip/right)))
                (conj universal-variables universal-variable))

              fact
              (do (swap! (:type-checker/facts module)
                    #(-> %
                       (zip/insert-right fact)
                       (zip/right)))
                  universal-variables)))
          []
          discard)
        generalized-type
        (->> universal-variables
          (reduce
            (fn [type universal-variable]
              {:ast/type :forall
               :variable universal-variable
               :body     type})
            type)
          (apply module))]
    generalized-type))

(defn- analysis:check
  [module term type-principality]
  (let [[type principality] (if-let [[type principality] (not-empty type-principality)]
                              [(apply module type) principality]
                              type-principality)]
    (match [term type principality]
      [{:ast/term :atom :atom atom}
       {:ast/type :existential-variable :id alpha}
       :non-principal]
      (let [type {:ast/type :primitive :primitive (:atom atom)}]
        (annotate-type term type)
        (solve-existential module alpha type))

      [{:ast/term :atom :atom atom}
       {:ast/type :primitive :primitive primitive}
       :principal]
      (when-not (= (:atom atom) primitive)
        (throw (ex-info "Distinct types" {:atom atom :primitive primitive})))

      [{:ast/term :variant :variant {:injector injector :value value}}
       {:ast/type :variant :injectors injectors}
       _]
      (let [injector* (select-keys injector [:reference :name])]
        (cond
          (and (some? value) (some? (get injectors injector*))) ; variant wraps value
          (analysis:check module value [(get injectors injector*) principality])

          (and (nil? value) (contains? injectors injector*)) ; variant is enum
          nil

          :default ; injector isn't defined
          (throw (ex-info "Unknown injector" {:injector injector})))
        (annotate-type term (apply module type)))

      [{:ast/term :variant :variant {:injector injector}}
       {:ast/type :existential-variable :id alpha}
       _]
      (let [current (zip/node @(:type-checker/facts module))
            _ (swap! (:type-checker/facts module)
                zip/focus-left
                {:fact/declare-existential alpha
                 :kind                     :kind/type})
            {:type/keys [name outer]}
            (-> module
              (module/all-injectors)
              (get injector))
            [type principality]
            (instantiate-universal module name outer)]
        (swap! (:type-checker/facts module) zip/focus-right current)
        (solve-existential module alpha type)
        (analysis:check module term [type principality]))

      [{:ast/term :lambda :argument argument :body body}
       {:ast/type :existential-variable :id alpha}
       :non-principal]          ; TODO: guard that `alpha` is declared
      (let [current  (zip/node @(:type-checker/facts module))
            _        (swap! (:type-checker/facts module)
                       zip/focus-left
                       {:fact/declare-existential alpha :kind :kind/type})
            mark     (fresh-mark module)
            alpha-1  (fresh-existential module)
            alpha-2  (fresh-existential module)
            function {:ast/type :function
                      :domain   alpha-1
                      :return   alpha-2}]
        (solve-existential module alpha function)
        (swap! (:type-checker/facts module) zip/focus-right current)
        (bind-symbol module argument alpha-1 :non-principal)
        (when-let [argument-type (:type argument)]
          (analysis:check module
            {:ast/term :symbol :symbol argument}
            [(apply module argument-type) :principal]))
        (analysis:check module body [alpha-2 :non-principal])
        (solve-existential module alpha (generalize module mark function) :kind/type)
        (annotate-type body (apply module alpha-2)))

      [{:ast/term :match :body body :branches branches} _ _]
      (let [[pattern-type pattern-principality] (synthesize module body)]
        (match:check module
          branches
          [(apply module pattern-type) pattern-principality]
          [(apply module type) principality])
        nil)

      [_ _ _]
      (let [[synth-type] (synthesize module term)
            polarity     (subtyping:join
                           (subtyping:polarity type)
                           (subtyping:polarity synth-type))]
        (subtype module polarity synth-type type)))))

(defn- subtyping:join
  [type-a type-b]
  (match [type-a type-b]
    [:positive _] :positive
    [:negative _] :negative
    [:neutral :neutral] :negative
    [:neutral polarity] polarity))

(defn- subtyping:polarity
  [type]
  (match type
    {:ast/type :forall} :negative
    {:ast/type :exists} :positive
    _ :neutral))

(defn- universally-quantified?
  [type]
  (match type
    {:ast/type :forall} true
    _ false))

(defn- existentially-quantified?
  [type]
  (match type
    {:ast/type :exists} true
    _ false))

(defn- quantified?
  [type]
  (or
    (universally-quantified? type)
    (existentially-quantified? type)))

(defn- polarity
  [type]
  (case (:ast/type type)
    :forall :negative
    :exists :positive
    :neutral))

(defn- negative?
  [type]
  (= :negative (polarity type)))

(defn- positive?
  [type]
  (= :positive (polarity type)))

(defn- unsolved?
  [module existential-variable]
  (not (contains? (existential-solutions module) existential-variable)))

(defn- before?
  [module & vars]
  (->> @(:type-checker/facts module)
    (zip/left-seq)
    (reverse)
    (keep (fn [fact] ((set vars) (:fact/declare-existential fact))))
    (= vars)))

(defn- instantiate-to
  [module type [solution kind]]
  (match [type [solution kind]]
    [({:ast/type :existential-variable :id alpha} :as alpha-type)
     [({:ast/type :existential-variable :id beta} :as beta-type) _]]
    (if (and (unsolved? module beta) (before? module alpha beta))
      (solve-existential module beta alpha-type kind) ; InstReach
      (solve-existential module alpha beta-type kind)) ; InstSolve

    [{:ast/type :existential-variable :id alpha}
     [{:ast/type :variant :injectors injectors} _]] ; InstBin
    (let [_ (swap! (:type-checker/facts module)
              zip/focus-left
              {:fact/declare-existential alpha :kind :kind/type})
          injectors*
          (->> injectors
            (map (fn [[injector value]]
                   [injector (when (some? value) (fresh-existential module))]))
            (into {}))]
      (solve-existential module alpha (assoc solution :injectors injectors*))
      (swap! (:type-checker/facts module) zip/->end)
      (dorun (map
               (fn [alpha-n tau-n]
                 (when (some? alpha-n)
                   (instantiate-to module alpha-n [(apply module tau-n) :kind/type])))
               (vals injectors*)
               (vals injectors))))

    [{:ast/type :existential-variable :id alpha}
     [{:ast/type :function} _]] ; InstBin
    (undefined :instantiate-to/inst-bin.function)

    [{:ast/type :existential-variable :id alpha} _]
    (let [solution (apply module solution)]
      ;; TODO: check for wellformedness
      (solve-existential module alpha solution kind)))) ; InstSolve

(defn- subtyping:equivalent
  [module type-a type-b]
  (match [type-a type-b]
    [{:ast/type :function :domain a-1 :return a-2}
     {:ast/type :function :domain b-1 :return b-2}] ; ≡⊕
    (do (subtyping:equivalent module a-1 b-1)
        (subtyping:equivalent module (apply module a-2) (apply module b-2)))

    [{:ast/type :variant :injectors injectors-1}
     {:ast/type :variant :injectors injectors-2}] ; ≡⊕
    (do (when-let [diff (not-empty (utils/symmetric-difference (set (keys injectors-1)) (set (keys injectors-2))))]
          (throw (ex-info "Different variant types" {:left type-a :right type-b :diff diff})))
        (merge-with
          (fn [type-1 type-2]
            (when (and (some? type-1) (some? type-2))
              (subtyping:equivalent module (apply module type-1) (apply module type-2))))
          injectors-1
          injectors-2)
        nil)

    [{:ast/type :quote :inner inner-1}
     {:ast/type :quote :inner inner-2}]
    (subtyping:equivalent module inner-1 inner-2)

    [{:ast/type :existential-variable} _] ; ≡InstantiateL
    (instantiate-to module type-a [type-b :kind/type])
 
    [_ {:ast/type :existential-variable}] ; ≡InstantiateR
    (instantiate-to module type-b [type-a :kind/type])

    [_ _]
    (if (not= type-a type-b)
      (undefined :subtyping.equivalent/fallthrough)
      nil)))

(defn- subtype
  [module polarity type-a type-b]
  (match [polarity type-a type-b]
    [_ (_ :guard (complement quantified?)) (_ :guard (complement quantified?))] ; <:Equiv
    (subtyping:equivalent module type-a type-b)

    [:negative {:ast/type :forall :variable universal-alpha :body a} (b :guard (complement universally-quantified?))] ; <:∀L
    (let [mark              (fresh-mark module)
          existential-alpha (fresh-existential module)]
      (subtype module :negative
        (walk/prewalk-replace {universal-alpha existential-alpha} a)
        b)
      (drop module mark)
      nil)

    [:negative a {:ast/type :forall :variable universal-beta :body b}] ; <:∀R
    (let [mark (fresh-mark module)]
      (subtype module :negative a b)
      (drop module mark)
      nil)

    [:positive (a :guard negative?) (b :guard (complement positive?))]
    (subtype module :negative a b)

    [:positive (a :guard (complement positive?)) (b :guard negative?)]
    (subtype module :negative a b)

    [:negative (a :guard positive?) (b :guard (complement negative?))]
    (subtype module :positive a b)

    [:negative (a :guard (complement negative?)) (b :guard positive?)]
    (subtype module :positive a b)

    [_ _ _]
    (throw (ex-info "Invalid subtype" {:polarity polarity :sub type-a :super type-b}))))

(defn- match:check
  [module branches pattern return]
  (let [apply*
        #(if-let [[type principality] (not-empty %)]
           [(apply module type) principality]
           %) 
        [pattern-type pattern-principality :as pattern] (apply* pattern)
        [return-type return-principality :as return]    (apply* return)]
    (match [branches pattern]
      [[{:pattern {:ast/pattern :atom :atom atom}}]
       [{:ast/type :existential-variable :id alpha} :non-principal]]
      (let [type {:ast/type :primitive :primitive (:atom atom)}]
        (solve-existential module alpha type)
        (match:check module branches [type :principal] return))

      [[{:pattern {:ast/pattern :atom :atom atom}
         :action action}]
       [{:ast/type :primitive :primitive primitive} _]]
      (if (= (:atom atom) primitive)
        (match:check module [{:action action}] [] return)
        (throw (ex-info "Distinct types" {:atom atom :primitive primitive})))

      [[{:pattern {:ast/pattern :variant :variant {:injector injector :value value}}
         :action  action}]
       [{:ast/type :variant :injectors injectors} principality]]
      (cond
        (and (some? value) (some? (get injectors injector)))
        (match:check module [{:pattern value :action action}] [(get injectors injector) principality] return)

        (and (nil? value) (contains? injectors injector))
        (match:check module [{:action action}] [] return)

        :default
        (throw (ex-info "Unknown injector" {:injector injector})))

      [[{:pattern {:ast/pattern :variant :variant {:injector injector}}}]
       [{:ast/type :existential-variable :id alpha} :non-principal]]
      (let [current (zip/node @(:type-checker/facts module))
            _       (swap! (:type-checker/facts module)
                      zip/focus-left
                      {:fact/declare-existential alpha
                       :kind                     :kind/type})
            {:type/keys [name outer]}
            (-> module
              (module/all-injectors)
              (get injector))
            [type principality]
            (instantiate-universal module name outer)]
        (swap! (:type-checker/facts module) zip/focus-right current)
        (solve-existential module alpha type)
        (match:check module branches [type principality] return))

      [[({:action action} :only [:action])]
       _]
      (analysis:check module action return)

      [([] :guard empty?)
       _]
      nil

      [[{:pattern {:ast/pattern :symbol :symbol symbol} :action action}]
       [type principality]]
      (let [mark (fresh-mark module)]
        (bind-symbol module symbol type principality)
        (match:check module [{:action action}] [] return)
        (drop module mark)
        nil)

      [[{:pattern {:ast/pattern :wildcard} :action action}]
       _]
      (match:check module [{:action action}] [] return)

      [[first-pattern & more-patterns]
       [pattern-type pattern-principality]]
      (do
        (match:check module [first-pattern] pattern return)
        (match:check module
          more-patterns
          [(apply module pattern-type) pattern-principality]
          [(apply module return-type) return-principality])))))

(defn- recover-spine
  [module arguments [type principality]]
  (match [arguments type]
    [[] _]
    [type principality]

    [[e & s] {:ast/type :function :domain domain :return return}]
    (do (analysis:check module e [domain principality])
        (recover-spine module s [(apply module return) principality]))

    [es {:ast/type :forall :variable universal-variable :body body}]
    (let [existential-variable (fresh-existential module)]
      (recover-spine module
        es
        [(walk/prewalk-replace {universal-variable existential-variable} body) principality]))

    [_ {:ast/type :existential-variable :id alpha}]
    (let [current  (zip/node @(:type-checker/facts module))
          _        (swap! (:type-checker/facts module)
                     zip/focus-left
                     {:fact/declare-existential alpha
                      :kind                     :kind/type})
          alpha-1  (fresh-existential module)
          alpha-2  (fresh-existential module)
          function {:ast/type :function
                    :domain   alpha-1
                    :return   alpha-2}]
      (solve-existential module alpha function :kind/type)
      (swap! (:type-checker/facts module) zip/focus-right current)
      (recover-spine module arguments [function principality]))

    [_ _] (undefined :recover-spine/fallthrough)))

(defn- to-jvm-type
  [type]
  (match type ; TODO: complete
    {:ast/type :object :class class}
    class

    {:ast/type :primitive :primitive primitive}
    (case primitive
      :string 'java.lang.String)))

(defn- from-jvm-type
  [type]
  (case type ; TODO: complete
    'void {:ast/type :primitive :primitive :unit}))

(defn expand-macro
  [module term]
  (match term
    {:ast/term :application :function {:symbol symbol}}
    (let [macro (lookup-macro module symbol)
          term* ((:expand macro) term)]
      (deliver (:macro/expands-to term) term*)
      term*)))

(defn- synthesize
  [module term]
  (let [[type principality]
        (match term
          {:ast/term :symbol :symbol (symbol :guard jvm/native?)}
          (let [class (->> symbol :in :name
                        (str/join ".")
                        (java.lang.Class/forName)
                        (clojure.reflect/reflect))
                field (first (filter #(= (:name symbol) (name (:name %))) (:members class)))]
            [{:ast/type :object :class (:type field)}
             :principal])

          {:ast/term :symbol :symbol symbol}
          (lookup-binding module symbol)

          {:ast/term :application
           :function {:symbol (symbol :guard (partial module/macro? module))}}
          (->> term
            (expand-macro module)
            (synthesize module))

          {:ast/term :application :function function :arguments arguments}
          (let [mark (fresh-mark module)
                [result-type result-principality]
                (->> function
                  (synthesize module)
                  (recover-spine module arguments))]
            [(generalize module mark result-type) result-principality])

          {:ast/term :access
           :object   object
           :field    {:ast/term  :application
                      :function  function
                      :arguments arguments}} ; instance method
          (let [object-type           (->> object (synthesize module) (first) (to-jvm-type))
                parameter-types       (mapv #(to-jvm-type (first (synthesize module %))) arguments)
                {:keys [return-type]} (->> function :symbol :in :name
                                        (str/join ".")
                                        (java.lang.Class/forName)
                                        (clojure.reflect/reflect)
                                        :members
                                        (filter (fn [member]
                                                  (and
                                                    (= (:declaring-class member) object-type)
                                                    (= (name (:name member)) (:name (:symbol function)))
                                                    (= (:parameter-types member) parameter-types))))
                                        (first))]
            (annotate-type function
              {:ast/type  :method
               :class     object-type
               :signature (conj parameter-types return-type)})
            [(from-jvm-type return-type) :principal])

          {:ast/term :access
           :object   object} ; static field
          (undefined ::static-field)

          {:ast/term :quote
           :body     body}
          (let [alpha (fresh-existential module)]
            (analysis:check module body [alpha :non-principal])
            [{:ast/type :quote :inner (get (existential-solutions module) alpha alpha)} :non-principal])

          {:ast/term :unquote
           :body body}
          (let [[type principality] (synthesize module body)]
            (match type
              {:ast/type :quote :inner inner}
              [inner principality]))

          {:ast/term _}
          (let [alpha (fresh-existential module)]
            (analysis:check module term [alpha :non-principal])
            [(get (existential-solutions module) alpha alpha) :non-principal]))

        _ (when (not (map? type)) (undefined ::messed-up-type))

        type* (apply module type)]
    (annotate-type term type*)
    [type* principality]))

(defn- abstract-type
  [module {:keys [params body] :as definition}]
  (apply module
    (if (empty? params)
      body
      (let [param-type->universal-variable
            (->> #(fresh-universal module)
              (repeatedly (count params))
              (zipmap (map (fn [param] {:ast/type :named :name param}) params)))]
        (->> params
          (rseq)
          (reduce
            (fn [type param]
              {:ast/type :forall
               :variable (get param-type->universal-variable {:ast/type :named :name param})
               :body     type})
            (->> body
              (walk/prewalk-replace param-type->universal-variable)
              (apply module))))))))

(defn run
  [module]
  (reduce
    (fn [module definition]
      (let [definition* (setup-annotations definition)]
        (match definition*
          {:ast/definition :type :name name}
          (let [type*       (abstract-type module definition*)
                definition* (assoc definition* :body type*)]
            (-> module
              (update :definitions conj definition*)
              (assoc-in [:types name :body] type*)
              (assoc-in [:types name :injectors] (type/injectors definition*))
              (assoc-in [:types name :fields] (type/fields definition*))))

          {:ast/definition :constant :name name :body expr}
          (let [mark                (fresh-mark module)
                [type principality] (synthesize module expr)
                discard             (drop module mark)] ; TODO: probably ok to throw away all facts
            (swap! (:type-checker/facts module) zip/->end)
            (-> module
              (assoc-in [:values name :type] [type :principal])
              (update :definitions conj (-> definition*
                                          (resolve-annotations)
                                          (assoc :type-checker/type type)))))

          {:ast/definition :macro    :name name
           :arguments      arguments :body body}
          (let [mark        (fresh-mark module)
                argument-types
                (mapv (fn [argument]
                        (let [alpha (fresh-existential module :kind/type)
                              type  {:ast/type :quote :inner alpha}]
                          (bind-symbol module argument type :non-principal)
                          type))
                  arguments)
                return-type (fresh-existential module)
                _           (analysis:check module body [return-type :non-principal])
                type  [(->> return-type
                         (conj argument-types)
                         (type/function)
                         (generalize module mark))
                       :non-principal]]
            (-> module
              (assoc-in [:macros name :type] type)
              (assoc-in [:macros name :expand]
                (fn [application]
                  (let [environment (zipmap arguments (:arguments application))]
                    (interpreter/run environment body))))
              (update :definitions conj definition))))))
    (merge module
      {:definitions                   []
       :type-checker/current-variable (atom 0)
       :type-checker/facts            (atom zip/empty)})
    (:definitions module)))
