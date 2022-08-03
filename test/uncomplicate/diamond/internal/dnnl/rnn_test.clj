;;   Copyright (c) Dragan Djuric. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php) or later
;;   which can be found in the file LICENSE at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns uncomplicate.diamond.internal.dnnl.rnn-test
  (:require [midje.sweet :refer [facts throws => roughly]]
            [uncomplicate.commons [core :refer [with-release]]]
            [uncomplicate.diamond.dnn-test :refer :all]
            [uncomplicate.diamond.internal.dnnl.factory :refer [dnnl-factory]])
  (:import clojure.lang.ExceptionInfo))

(with-release [fact (dnnl-factory)]
  (test-vanilla-rnn-inference fact)
  (test-vanilla-rnn-inference-no-iter fact)
  (test-vanilla-rnn-training fact)
  (test-vanilla-rnn-training-zero-iter fact)
  (test-vanilla-rnn-training-no-iter fact) ;; TODO DNNL fails
  (test-rnn-inference fact)
  (test-rnn-training fact) ;; TODO DNNL fails
  (test-rnn-training-no-iter fact)
  (test-lstm-training-no-iter fact)
  (test-lstm-training-no-iter-adam fact)
  (test-gru-training-no-iter-adam fact)
  (test-ending fact))

(with-release [fact (dnnl-factory)]
  (test-rnn-training fact) ;; TODO now CUDNN matches zero-iter!, but DNNL doesn't!
  )
