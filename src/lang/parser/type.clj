(ns lang.parser.type
  (:require [blancas.kern.core :refer :all]
            [lang.parser.lexer :refer :all]
            [lang.parser.reference :as reference]))

(declare expr)

(def ^:private named
  (bind [name reference/type]
    (return {:ast/type :named :name name})))

(def ^:private variant
  (parens
    (bind [_ (sym \|)
           variants (many1 (brackets (<*> reference/keyword (fwd expr))))]
      (return {:ast/type :variant :variants (into (array-map) variants)}))))

(def expr
  (<|>
    named
    variant))