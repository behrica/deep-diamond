;;   Copyright (c) Dragan Djuric. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php) or later
;;   which can be found in the file LICENSE at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns uncomplicate.diamond.internal.dnnl.core
  (:require [uncomplicate.commons
             [core :refer [let-release with-release extract view Info bytesize]]
             [utils :refer [enc-keyword direct-buffer capacity dragan-says-ex mask]]]
            [uncomplicate.clojure-cpp
             :refer [int-pointer long-pointer float-pointer pointer-pointer get-entry element-count
                     fill! pointer-vec safe byte-pointer position! pointer get-pointer put-entry!]]
            [uncomplicate.diamond.internal.utils :refer [default-strides]]
            [uncomplicate.diamond.internal.dnnl
             [impl :refer :all]
             [constants :refer :all]
             [protocols :refer :all]])
  (:import clojure.lang.ExceptionInfo
           org.bytedeco.dnnl.global.dnnl
           [org.bytedeco.dnnl dnnl_engine dnnl_memory_desc dnnl_exec_arg_t]
           uncomplicate.diamond.internal.dnnl.impl.MemoryImpl))

;; ===================== Engine ===============================================

(defn engine
  "Creates an engine for the device `id` of the specified keyword `kind`.

   Supported engine kinds are `:cpu`, `:gpu`, and `:any`. The default kind is `:cpu`.
   Engine has to be `release`d.

   Throws an ExceptionInfo if the `id` does not correspond to a physical device
   or if `kind` is not supported."
  ([^long id kind]
   (engine* id (enc-keyword dnnl-engine-kind kind)))
  ([^long id]
   (engine* id))
  ([]
   (engine 0)))

(defn engine-count
  "Returns the number of physical engines of the specified `kind` (`:cpu`, `:gpu`, `:any`).

  Throws an ExceptionInfo if `kind` is not supported."
  (^long []
   (engine-count*))
  (^long [kind]
   (engine-count* (enc-keyword dnnl-engine-kind kind))))

(defn engine-kind
  "Returns engine's kind as a keyword. Typical values are `:gpu` and `:cpu`.

  Throws an ExceptionInfo if `kind` is not supported."
  ([eng]
   (dec-engine-kind (engine-kind* (extract eng)))))

(defn primitive-cache-capacity! [n]
  (with-check (dnnl/dnnl_set_primitive_cache_capacity (int n))
    n))

(defn primitive-cache-capacity []
  (let [res (int-pointer 1)]
    (with-check (dnnl/dnnl_get_primitive_cache_capacity res)
      (get-entry res 0))))

;; ===================== Stream ===============================================

(defn stream
  "Creates a stream for executing primitive operations for engine `eng`.

  Stream execution can be further specified by `flags`, defined in the
  [[constants/dnnl-stream-flags]].
  Stream has to be `release`d."
  [eng & flags]
  (if flags
    (stream* (extract eng) (mask dnnl-stream-flags flags))
    (stream* (extract eng))))

(defn wait!
  "Waits until stream `s` completes execution of all queued operations."
  [strm]
  (wait* (extract strm)))

(defn execute!
  "Queues the operation primitive `p` for execution in stream `strm`.

  Returns `strm`. Throws an ExceptionInfo if the DNNL stream is not valid,
  or the primitive cannot be executed."
  [strm p args]
  (execute* (extract strm) (extract p) args))

;; ===================== Memory ===============================================

(defn memory-desc
  "Creates an engine-agnostic, logical, description of data, based on dimensions,
  data type and data format.

  `dims` is a Clojure vector of positive numbers representing dimensions in the
  `:abcdef` format, regardless of the physical layout of dimensions.
  `data-type` is a keyword that specifies one of the supported types of data,
  defined in [[`constants/dnnl-data-type`]] (`:float`, `:int`, etc.)
  `format` specifies an (optional) physical layout as a keyword, choosing one
  of [[`constants/dnnl-format`]] (`:nchw`, `:acdeb`, `:any`, etc.), or through
  strides specified as a Clojure vector of positive numbers that match logical
  dimensions.

  Examples:

  (memory-desc [2 3] :float :nc)

  (memory-desc [2 3 4 5] :float [120 3 4 5])
  "
  ([dims data-type format]
   (with-release [dims (long-pointer dims)
                  fmt (if (keyword? format)
                        (enc-keyword dnnl-format format)
                        (long-pointer (drop (- (count format) (element-count dims)) format)))]
     (memory-desc* (extract fmt) (extract dims) (enc-keyword dnnl-data-type data-type))))
  ([dims format]
   (memory-desc dims :float format))
  ([dims]
   (memory-desc dims :float :any))
  ([]
   (dnnl_memory_desc.)))

(defn submemory-desc
  "Creates a (sub)memory section of a memory object, using the specified
  shape `dims`, and `offsets` vectors."
  ([parent-desc dims offsets]
   (with-release [dims (long-pointer dims)
                  offsets (long-pointer offsets)]
     (submemory-desc* (desc parent-desc) (extract dims) (extract offsets))))
  ([parent-desc dim]
   (if (number? dim)
     (submemory-desc* (desc parent-desc) dim)
     (with-release [dims (long-pointer dim)
                    offsets (fill! (long-pointer (element-count dims)) 0)]
       (submemory-desc* (desc parent-desc) (extract dims) (extract offsets))))))

(defn equal-desc?
  "Compares two memory descriptors for logical equality.

  Two descriptors may be equal even though the objects are not
  equal nor identical in the JVM sense.
  "
  [x y]
  (let [x (desc x)
        y (desc y)]
    (or (= x y) (= 1 (dnnl/dnnl_memory_desc_equal x y)))))

(def zero-desc (memory-desc [] :undef []))

(defn zero-desc? [mem-desc]
  (or (nil? mem-desc) (equal-desc? zero-desc (desc mem-desc))))

(defn data-type
  "Queries the data type of a memory descriptor."
  [mem-desc]
  (dec-data-type (data-type* (desc mem-desc))))

(defn ndims
  "Queries the number of dimensions of a memory descriptor."
  ^long [mem-desc]
  (ndims* (desc mem-desc)))

(defn dims
  "Queries the dimensions of a memory descriptor."
  [mem-desc]
  (with-release [res (dims* (desc mem-desc))]
    (pointer-vec res)))

(defn strides
  "Queries the strides of a memory descriptor."
  [mem-desc]
  (try
    (with-release [res (strides* (desc mem-desc))]
      (pointer-vec res))
    (catch ExceptionInfo e
      (if (= :invalid-arguments (:error (ex-data e)))
        (vec (long-array (ndims* (desc mem-desc))))))))

(defmacro extend-memory-desc-info [t]
  `(extend-type ~t
     Info
     (info
       ([this# info-type#]
        (case info-type#
          :class (class this#)
          :device :cpu
          :shape (dims this#)
          :data-type (data-type this#)
          :strides (strides this#)
          nil))
       ([this#]
        {:class (class this#)
         :device :cpu
         :shape (dims this#)
         :data-type (data-type this#)
         :strides (strides this#)}))))

(extend-memory-desc-info dnnl_memory_desc)
(extend-memory-desc-info MemoryImpl)

(defn memory
  "An engine-specific memory handle for a raw buffer and a matching descriptor.

  `eng` a DNNL engine that controls the context.
  `mem-desc` logical memory descriptor.
  `buf` JavaCPP pointer instance.
  `master` indicates whether this memory object handles the life cycle of `buf`."
  ([eng mem-desc buf master]
   (if (<= (bytesize (desc mem-desc)) (bytesize buf))
     (memory* (desc mem-desc) (extract eng) (safe buf) master)
     (dragan-says-ex "The buffer has to be large enough for mem-desc"
                     {:desc-bytes (bytesize (desc mem-desc)) :buffer-bytes (bytesize buf)})))
  ([eng mem-desc buf]
   (memory eng mem-desc buf false))
  ([eng mem-desc]
   (let-release [buf (byte-pointer (bytesize (desc mem-desc)))]
     (memory* (desc mem-desc) eng (safe buf) true))))

(defn offset! ;; TODO consider getting rid of offset! (if possible) by using pointer methods
  "Sets the starting position in the buffer that the memory object `mem` controls."
  [mem ^long n]
  (let [p (pointer mem)]
    (if (<= 0 n (bytesize p))
      (with-check (dnnl/dnnl_memory_set_data_handle (extract mem) (extract (position! p n)))
        mem)
      (dragan-says-ex "There is not enough capacity in the underlying buffer for this offset."
                      {:requested n :available (bytesize p)}))))

(defn offset ;;TODO remove
  "Gets the starting position in the buffer that the memory object `mem` controls."
  ^long [mem]
  (ex-info "offset should be replaced with Pointer.position()" nil))

(defn get-engine
  "Returns the engine context of the memory object `mem`."
  [mem]
  (get-engine* (extract mem)))

(defn dnnl-contiguous-desc [md]
  (let [s (dims md)]
    (if (and (= :float (data-type md)) (= (bytesize md) (apply * Float/BYTES s)))
      (view md)
      (memory-desc s :float (default-strides s)))))

;; ===================== Desc =================================================

(defn primitive-kind
  "Queries `desc` for the kind of primitive that it describes, returned as
  keyword.

  Result is one of the keywords defined in [[constants/dec-primitive-kind]],
  typically `:inner-product`, `:convolution`, `:elementwise`, etc."
  [desc]
  (dec-primitive-kind (primitive-kind* desc)))

;; ===================== Primitive ============================================

(defn primitive
  "Creates a primitive from the primitive descriptor `pd`.

  Primitive encapsulates a pre-generated computation optimized for particular
  data shapes defined in the primitive descriptor. Usually, such primitive is
  executed many times with the data of these shapes, while the preparation cost
  is paid only at the time of creation.

  Primitive is a function with execution context (state). In addition to immutable
  state such as input and output shape and data type, it could require a mutable
  temporary work memory buffer that is called scratchpad in DNNL terminology.

  For more info about DNNL's concepts, see
  [the official DNNL guide](https://intel.github.io/mkl-dnn/dev_guide_basic_concepts.html).
  "
  [pd]
  (primitive* (extract pd)))

;; =================== Query ====================================================

(defn query-md
  "Queries the primitive descriptor `pd` for the property `what` and (optional) index `index`."
  ([pd what index]
   (let [index (if (= :exec-arg-md what) (dnnl-arg index index))
         d (query-md* (extract pd) (dnnl-query what what) index)]
     (if (zero-desc? d) nil d)))
  ([pd what]
   (let [d (query-md* (extract pd) (dnnl-query what what))]
     (if (zero-desc? d) nil d))))

(defn arg-md
  "Queries the primitive descriptor `pd` for the argument's memory descriptor."
  [pd arg]
  (let [d (query-md* (extract pd) dnnl/dnnl_query_exec_arg_md (dnnl-arg arg arg))]
    (if (zero-desc? d) nil d)))

(defn src-md
  "Queries the primitive descriptor `pd` for the source (input)."
  [pd]
  (let [d (query-md* (extract pd) dnnl/dnnl_query_src_md)]
    (if (zero-desc? d) nil d)))

(defn diff-src-md
  "Queries the primitive descriptor `pd` for the gradient of the source (input)."
  [pd]
  (let [d (query-md* (extract pd) dnnl/dnnl_query_diff_src_md)]
    (if (zero-desc? d) nil d)))

(defn weights-md
  "Queries the primitive descriptor `pd` for the weights."
  [pd]
  (let [d (query-md* (extract pd) dnnl/dnnl_query_weights_md)]
    (if (zero-desc? d) nil d)))

(defn diff-weights-md
  "Queries the primitive descriptor `pd` for the gradient of the weights."
  [pd]
  (let [d (query-md* (extract pd) dnnl/dnnl_query_diff_weights_md)]
    (if (zero-desc? d) nil d)))

(defn dst-md
  "Queries the primitive descriptor `pd` for the destination (output)."
  [pd]
  (let [d (query-md* (extract pd) dnnl/dnnl_query_dst_md)]
    (if (zero-desc? d) nil d)))

(defn diff-dst-md
  "Queries the primitive descriptor `pd` for the gradient of the destination (output)."
  [pd]
  (let [d (query-md* (extract pd) dnnl/dnnl_query_diff_dst_md)]
    (if (zero-desc? d) nil d)))

(defn workspace-md
  "Queries the primitive descriptor `pd` for the workspace (scratchpad)."
  [pd]
  (let [d (query-md* (extract pd) dnnl/dnnl_query_workspace_md)]
    (if (zero-desc? d) nil d)))

;; =================== Etlwise ==================================================

(defn eltwise-fwd
  "Creates a forward descriptor of an operation that is applied to
  every element of a tensor.

  * `prop-kind`: the kind of propagation: `:inference`, `training`, or `:scoring`
  (defined in `[[constants/dnnl-forward-prop-kind]]`)
  * `alg-kind`: operation algorithm, such as `:relu` or `:logistic`
  (defined in `[[constants/dnnl-eltwise-alg-kind]]`)
  * `mem-desc`: the descriptor that defines memory layout of the data
  * `alpha`, and `beta`: optional coefficients, depending on `alg-kind`."
  ([prop-kind alg-kind mem-desc alpha beta eng]
   (eltwise-forward* (extract eng) (enc-keyword dnnl-forward-prop-kind prop-kind)
                     (enc-keyword dnnl-eltwise-alg-kind alg-kind)
                     (desc mem-desc) alpha beta nil))
  ([prop-kind alg-kind mem-desc eng]
   (eltwise-forward* (extract eng) (enc-keyword dnnl-forward-prop-kind prop-kind)
                     (enc-keyword dnnl-eltwise-alg-kind alg-kind)
                     (desc mem-desc) 0.0 0.0 nil)))

(defn eltwise-bwd
  "Creates a backward descriptor of an operation that is applied to
  every element of a tensor. Used only during the training.

  * `alg-kind`: operation algorithm, such as `:relu` or `:logistic`
  (defined in `[[constants/dnnl-eltwise-alg-kind]]`)
  * `diff-desc`: the diff memory descriptor
  * `src-desc`: the source memory descriptor
  * `dst-desc`: the destination memory descriptor
  * `alpha`, and `beta`: optional coefficients, depending on `alg-kind`."
  ([alg-kind diff-desc src-desc alpha beta eng hint-fwd-pd]
   (eltwise-backward* (extract eng) (enc-keyword dnnl-eltwise-alg-kind alg-kind)
                      (desc diff-desc) (desc src-desc) alpha beta (extract hint-fwd-pd) nil))
  ([alg-kind diff-desc src-desc eng hint-fwd-pd]
   (eltwise-backward* (extract eng) (enc-keyword dnnl-eltwise-alg-kind alg-kind)
                      (desc diff-desc) (desc src-desc) 0.0 0.0 (extract hint-fwd-pd) nil)))

(defn eltwise-bwd-args
  "Creates DNNL's data structure that holds arguments as required by
  elementwise operations."
  [src diff-dst diff-src]
  (let-release [args (dnnl_exec_arg_t. 3)]
    (args* args 0 dnnl/DNNL_ARG_SRC (extract src))
    (args* args 1 dnnl/DNNL_ARG_DIFF_DST (extract diff-dst))
    (args* args 2 dnnl/DNNL_ARG_DIFF_SRC (extract diff-src))))

;; ======================= Sum ============================================================

(defn sum!
  "Scales a single `dst`, or sums scaled entries of more tensors elementwise.

  This operation changes `dst`. All sources and destinations have to be of
  the same shape.

  BEWARE: if `dst` and one of the `src`s are identical, this source has to
  be the first `src` argument, due to how DNNL algorithm works internally,
  or result would be incorrect!

  `eng`: the computing context engine
  `scale`: a floating point scale for the first source

  If only a single tensor is provided, computes dst = scale * dst.
  `dst`: the source and destination tensor

  Otherwise, computes dst = scale * src + scale-srcs[0] * scale-srcs[1] etc.
  `dst`: the source and destination tensor
  `src`: the first source tensor
  `scale-srcs`: a sequence of `scale1,` `src1`, `scale2`, `src2`, etc.

  Example:
  (sum eng md 2.0 md 3.0 md)
  "
  ([eng scale dst]
   (with-release [scale (float-pointer [scale])]
     (sum* (extract eng) (desc dst) scale (desc dst) nil)))
  ([eng dst scale src & scale-srcs]
   (let [srcs (mapv desc (cons src (take-nth 2 (rest scale-srcs))))
         n (count srcs)]
     (with-release [s (pointer-pointer n)
                    scale (float-pointer (cons scale (take-nth 2 scale-srcs)))]
       (dotimes [i n]
         (put-entry! s i (srcs i)))
       (sum-pp* (extract eng) (desc dst) (extract scale) (extract s) nil)))))

;; ======================= Binary op ============================================================

(defn binary
  "TODO
  NOTE: much slower than Neanderthal add or mul. Use only when can't avoid it."
  ([alg-kind src0-desc src1-desc dst-desc]
   (binary* (enc-keyword dnnl-binary-alg-kind alg-kind)
                 (desc src0-desc) (desc src1-desc) (desc dst-desc)))
  ([alg-kind src-dst-desc src1-desc]
   (binary alg-kind src-dst-desc src1-desc src-dst-desc))
  ([alg-kind src-dst-desc]
   (binary alg-kind src-dst-desc src-dst-desc src-dst-desc)))

(defn binary-args
  ([src0 src1 dst]
   (let-release [args (dnnl_exec_arg_t. 3)]
     (args* args 0 dnnl/DNNL_ARG_SRC_0 (extract src0))
     (args* args 1 dnnl/DNNL_ARG_SRC_1 (extract src1))
     (args* args 2 dnnl/DNNL_ARG_DST (extract dst))))
  ([src-and-dst src1]
   (binary-args src-and-dst src1 src-and-dst)))

;; ========================= Execution Arguments =======================================

(defn args [arg-map]
  (let-release [args (dnnl_exec_arg_t. (count arg-map))]
    (doseq [[k v i] (map conj arg-map (range))]
      (args* args i (dnnl-arg k k) (extract v)))
    args))

(defn multi-args
  "Creates DNNL's data structure that holds arguments for various
  operations that accept one destination and one or multiple sources."
  ([src-and-dst]
   (let-release [args (dnnl_exec_arg_t. 2)]
     (args* args 0 dnnl/DNNL_ARG_MULTIPLE_SRC (extract src-and-dst))
     (args* args 1 dnnl/DNNL_ARG_DST (extract src-and-dst))))
  ([dst src]
   (let-release [args (dnnl_exec_arg_t. 2)]
     (args* args 0 dnnl/DNNL_ARG_DST (extract dst))
     (args* args 1 dnnl/DNNL_ARG_MULTIPLE_SRC (extract src))))
  ([dst src0 src1]
   (let-release [args (dnnl_exec_arg_t. 3)]
     (args* args 0 dnnl/DNNL_ARG_DST (extract dst))
     (args* args 1 dnnl/DNNL_ARG_MULTIPLE_SRC (extract src0))
     (args* args 2 (inc dnnl/DNNL_ARG_MULTIPLE_SRC) (extract src1))))
  ([dst src0 src1 & srcs]
   (let [cnt (+ 3 (count srcs))]
     (let-release [args (dnnl_exec_arg_t. cnt)]
       (args* args 0 dnnl/DNNL_ARG_DST (extract dst))
       (args* args 1 dnnl/DNNL_ARG_MULTIPLE_SRC (extract src0))
       (args* args 2 (inc dnnl/DNNL_ARG_MULTIPLE_SRC) (extract src1))
       (doall (map (fn [^long i src]
                     (args* args i (+ dnnl/DNNL_ARG_MULTIPLE_SRC (dec i)) (extract src)))
                   (range 3 cnt) srcs))
       args))))

(defn fwd-args
  "Creates DNNL's data structure that holds arguments as required by
  forward operations."
  ([src-and-dst]
   (let-release [args (dnnl_exec_arg_t. 2)]
     (args* args 0 dnnl/DNNL_ARG_SRC (extract src-and-dst))
     (args* args 1 dnnl/DNNL_ARG_DST (extract src-and-dst))))
  ([src dst]
   (let-release [args (dnnl_exec_arg_t. 2)]
     (args* args 0 dnnl/DNNL_ARG_SRC (extract src))
     (args* args 1 dnnl/DNNL_ARG_DST (extract dst))))
  ([src dst workspace]
   (let-release [args (dnnl_exec_arg_t. (if workspace 3 2))]
     (when workspace
       (args* args 2 dnnl/DNNL_ARG_WORKSPACE (extract workspace)))
     (args* args 0 dnnl/DNNL_ARG_SRC (extract src))
     (args* args 1 dnnl/DNNL_ARG_DST (extract dst))))
  ([src weights bias dst]
   (let-release [args (dnnl_exec_arg_t. 4)]
     (args* args 0 dnnl/DNNL_ARG_SRC (extract src))
     (args* args 1 dnnl/DNNL_ARG_WEIGHTS (extract weights))
     (args* args 2 dnnl/DNNL_ARG_BIAS (extract bias))
     (args* args 3 dnnl/DNNL_ARG_DST (extract dst)))))

(defn bwd-args
  ([diff-dst weights diff-src]
   (let-release [args (dnnl_exec_arg_t. 3)]
     (args* args 0 dnnl/DNNL_ARG_DIFF_DST (extract diff-dst))
     (args* args 1 dnnl/DNNL_ARG_WEIGHTS (extract weights))
     (args* args 2 dnnl/DNNL_ARG_DIFF_SRC (extract diff-src))))
  ([src diff-dst diff-weights diff-bias]
   (let-release [args (dnnl_exec_arg_t. 4)]
     (args* args 0 dnnl/DNNL_ARG_SRC (extract src))
     (args* args 1 dnnl/DNNL_ARG_DIFF_DST (extract diff-dst))
     (args* args 2 dnnl/DNNL_ARG_DIFF_WEIGHTS (extract diff-weights))
     (args* args 3 dnnl/DNNL_ARG_DIFF_BIAS (extract diff-bias)))))

;; ========================= Reorder ============================================

(defn reorder
  "Copies data across engines, between physical memory formats, keeping the
  logical structure of the tensor."
  ([input-eng input output-eng output]
   (reorder* (desc input) (extract input-eng) (desc output) (extract output-eng)))
  ([eng input output]
   (reorder eng input eng output)))

;; ======================== Inner Product =======================================

(defn inner-product-fwd
  "Creates a descriptor for the forward phase of the inner product operation,
  which computes `dst <- src * weights + bias`.

  `prop-kind`: one of the values defined in [[constants/dnnl-forward-prop-kind]]
  (`:inference`, `:training`, `:scoring`).
  `src-desc`: descriptor of the source (input) memory.
  `weights-desc`: descriptor of the weights memory.
  `bias-desc`: descriptor of the bias memory.
  `dst-desc`: descripror of the destination (output) memory.
  "
  [prop-kind src-desc weights-desc bias-desc dst-desc eng]
  (inner-product-forward* eng (enc-keyword dnnl-forward-prop-kind prop-kind)
                          (desc src-desc) (desc weights-desc)
                          (desc bias-desc) (desc dst-desc) nil))

(defn inner-product-bwd
  "Creates a descriptor for the backward phase of the inner product operation,
  for data (3-arguments) weights (5-arguments) updates.

  - The gradient of data computes `diff-src <- f(weights, diff-dst)`:
  `diff-src-desc`: descriptor of the source gradient (input) memory.
  `weights-desc`: descriptor of the weights memory.
  `diff-dst-desc`: descriptor of the destination gradient (output) memory.

  - The gradient of data computes `diff-weights <- f(diff-dst, src)`,
  and `diff-bias <- f(diff-dst, src)`:
  `src-desc`: descriptor of the source (input) memory.
  `diff-weights-desc`: descriptor of the weights gradient memory.
  `diff-bias-desc`: descriptor of the bias gradient memory.
  `diff-dst-desc`: descriptor of the destination gradient (output) memory.
  "
  ([diff-src-desc weights-desc diff-dst-desc eng hint-fwd-pd]
   (inner-product-backward-data* eng (desc diff-src-desc) (desc weights-desc)
                                 (desc diff-dst-desc) (extract hint-fwd-pd) nil))
  ([src-desc diff-weights-desc diff-bias-desc diff-dst-desc eng hint-fwd-pd]
   (inner-product-backward-weights* eng (desc src-desc) (desc diff-weights-desc)
                                    (desc diff-bias-desc) (desc diff-dst-desc)
                                    (extract hint-fwd-pd) nil)))

;; ================= Softmax ====================================================

(defn softmax-fwd
  "TODO"
  [prop-kind alg-kind mem-desc axis eng]
  (softmax-forward* eng (enc-keyword dnnl-forward-prop-kind prop-kind)
                    (enc-keyword dnnl-softmax-alg-kind alg-kind) (desc mem-desc) axis nil))

(defn softmax-bwd
  "TODO"
  [alg-kind diff-desc src-desc axis eng hint-fwd-pd]
  (softmax-backward* eng (enc-keyword dnnl-softmax-alg-kind alg-kind)
                     (desc diff-desc) (desc src-desc) axis hint-fwd-pd nil))

(defn softmax-bwd-args
  "Creates DNNL's data structure that holds arguments as required by
  softmax operations."
  ([dst diff-dst diff-src]
   (let-release [args (dnnl_exec_arg_t. 3)]
     (args* args 0 dnnl/DNNL_ARG_DST (extract dst))
     (args* args 1 dnnl/DNNL_ARG_DIFF_DST (extract diff-dst))
     (args* args 2 dnnl/DNNL_ARG_DIFF_SRC (extract diff-src))))
  ([src dst diff-dst diff-src]
   (let-release [args (dnnl_exec_arg_t. 3)]
     (args* args 0 dnnl/DNNL_ARG_DST (extract dst))
     (args* args 1 dnnl/DNNL_ARG_DIFF_DST (extract diff-dst))
     (args* args 2 dnnl/DNNL_ARG_DIFF_SRC (extract diff-src))
     (args* args 3 dnnl/DNNL_ARG_SRC (extract src)))))

;; ====================== Convolution ===========================================

(defn convolution-fwd
  "TODO"
  ([prop-kind alg-kind src-desc weights-desc bias-desc dst-desc
    strides dilates padding-l padding-r eng]
   (with-release [strides (long-pointer strides)
                  dilates (if dilates (long-pointer dilates)
                              (fill! (long-pointer (element-count strides)) 0))
                  padding-l (long-pointer padding-l)
                  padding-r (long-pointer padding-r)]
     (convolution-forward* (extract eng)
                           (enc-keyword dnnl-forward-prop-kind prop-kind)
                           (enc-keyword dnnl-convolution-alg-kind alg-kind)
                           (desc src-desc) (desc weights-desc) (desc bias-desc)
                           (desc dst-desc)
                           (extract strides) (extract dilates) (extract padding-l) (extract padding-r)
                           nil)))
  ([prop-kind alg-kind src-desc weights-desc bias-desc dst-desc strides dilates padding eng]
   (convolution-fwd prop-kind alg-kind src-desc weights-desc bias-desc dst-desc
                    strides dilates padding padding eng))
  ([prop-kind alg-kind src-desc weights-desc bias-desc dst-desc strides padding eng]
   (convolution-fwd prop-kind alg-kind src-desc weights-desc bias-desc dst-desc
                    strides nil padding padding eng)))

(defn convolution-bwd-data
  "TODO"
  ([alg-kind diff-src-desc weights-desc diff-dst-desc
    strides dilates padding-l padding-r eng hint-fwd-pd]
   (with-release [strides (long-pointer strides)
                  dilates (if dilates (long-pointer dilates)
                              (fill! (long-pointer (element-count strides)) 0))
                  padding-l (long-pointer padding-l)
                  padding-r (long-pointer padding-r)]
     (convolution-backward-data* (extract eng)
                                 (enc-keyword dnnl-convolution-alg-kind alg-kind)
                                 (desc diff-src-desc) (desc weights-desc) (desc diff-dst-desc)
                                 (extract strides) (extract dilates) (extract padding-l) (extract padding-r)
                                 (extract hint-fwd-pd) nil)))
  ([alg-kind diff-src-desc weights-desc diff-dst-desc strides dilates padding eng hint-fwd-pd]
   (convolution-bwd-data alg-kind diff-src-desc weights-desc diff-dst-desc
                         strides dilates padding padding eng hint-fwd-pd))
  ([alg-kind diff-src-desc weights-desc diff-dst-desc strides padding eng hint-fwd-pd]
   (convolution-bwd-data alg-kind diff-src-desc weights-desc diff-dst-desc
                         strides nil padding padding eng hint-fwd-pd)))

(defn convolution-bwd-weights
  "TODO"
  ([alg-kind src-desc diff-weights-desc diff-bias-desc diff-dst-desc
    strides dilates padding-l padding-r eng hint-fwd-pd]
   (with-release [strides (long-pointer strides)
                  dilates (if dilates (long-pointer dilates)
                              (fill! (long-pointer (element-count strides)) 0))
                  padding-l (long-pointer padding-l)
                  padding-r (long-pointer padding-r)]
     (convolution-backward-weights* (extract eng)
                                    (enc-keyword dnnl-convolution-alg-kind alg-kind)
                                    (desc src-desc) (desc diff-weights-desc)
                                    (desc diff-bias-desc) (desc diff-dst-desc)
                                    (extract strides) (extract dilates) (extract padding-l)
                                    (extract padding-r) (extract hint-fwd-pd) nil)))
  ([alg-kind src-desc diff-weights-desc diff-bias-desc diff-dst-desc
    strides dilates padding eng hint-fwd-pd]
   (convolution-bwd-weights alg-kind src-desc diff-weights-desc diff-bias-desc diff-dst-desc
                            strides dilates padding padding eng hint-fwd-pd))
  ([alg-kind src-desc diff-weights-desc diff-bias-desc diff-dst-desc strides padding eng hint-fwd-pd]
   (convolution-bwd-weights alg-kind src-desc diff-weights-desc diff-bias-desc diff-dst-desc
                            strides nil padding padding eng hint-fwd-pd)))

;; ====================== Pooling ===========================================

(defn pooling-fwd
  "TODO"
  ([prop-kind alg-kind src-desc dst-desc kernel strides dilates padding-l padding-r eng]
   (with-release [strides (long-pointer strides)
                  kernel(long-pointer kernel)
                  dilates (if dilates (long-pointer dilates)
                              (fill! (long-pointer (element-count strides)) 0))
                  padding-l (long-pointer padding-l)
                  padding-r (long-pointer padding-r)]
     (pooling-forward* (extract eng) (enc-keyword dnnl-forward-prop-kind prop-kind)
                       (enc-keyword dnnl-pooling-alg-kind alg-kind)
                       (desc src-desc) (desc dst-desc)
                       strides kernel dilates padding-l padding-r nil)))
  ([prop-kind alg-kind src-desc dst-desc kernel strides dilates padding eng]
   (pooling-fwd prop-kind alg-kind src-desc dst-desc kernel strides dilates padding padding eng))
  ([prop-kind alg-kind src-desc dst-desc kernel strides padding eng]
   (pooling-fwd prop-kind alg-kind src-desc dst-desc kernel strides nil padding padding eng)))

(defn pooling-bwd
  "TODO"
  ([alg-kind diff-src-desc diff-dst-desc kernel strides dilates padding-l padding-r eng hint-fwd-pd]
   (with-release [strides (long-pointer strides)
                  kernel(long-pointer kernel)
                  dilates (if dilates (long-pointer dilates)
                              (fill! (long-pointer (element-count strides)) 0))
                  padding-l (long-pointer padding-l)
                  padding-r (long-pointer padding-r)]
     (pooling-backward* (extract eng) (enc-keyword dnnl-pooling-alg-kind alg-kind)
                        (desc diff-src-desc) (desc diff-dst-desc)
                        strides kernel dilates padding-l padding-r (extract hint-fwd-pd) nil)))
  ([alg-kind diff-src-desc diff-dst-desc kernel strides dilates padding eng hint-fwd-pd]
   (pooling-bwd alg-kind diff-src-desc diff-dst-desc kernel strides dilates padding padding eng hint-fwd-pd))
  ([alg-kind diff-src-desc diff-dst-desc kernel strides padding eng hint-fwd-pd]
   (pooling-bwd alg-kind diff-src-desc diff-dst-desc kernel strides nil padding padding eng hint-fwd-pd)))

(defn pooling-bwd-args
  "TODO"
  [diff-dst diff-src workspace]
  (let-release [args (dnnl_exec_arg_t. (if workspace 3 2))]
    (when workspace
      (args* args 2 dnnl/DNNL_ARG_WORKSPACE (extract workspace)))
    (args* args 0 dnnl/DNNL_ARG_DIFF_DST (extract diff-dst))
    (args* args 1 dnnl/DNNL_ARG_DIFF_SRC (extract diff-src))))

;; ====================== Batch Normalization ===========================================

(defn batch-norm-fwd-desc
  "TODO"
  [prop-kind data-desc & flags]
  (batch-normalization-forward-desc* (enc-keyword dnnl-forward-prop-kind prop-kind)
                                     (desc data-desc) 1e-8
                                     (mask dnnl-normalization-flags flags)))

(defn batch-norm-bwd-desc
  "TODO"
  [prop-kind diff-data-desc data-desc & flags]
  (batch-normalization-backward-desc* (enc-keyword dnnl-backward-prop-kind prop-kind)
                                      (desc diff-data-desc) (desc data-desc)
                                      1e-8 (mask dnnl-normalization-flags flags)))

(defn batch-norm-fwd-args
  ([src-and-dst]
   (batch-norm-fwd-args src-and-dst src-and-dst))
  ([src dst]
   (let-release [args (dnnl_exec_arg_t. 2)]
     (args* args 0 dnnl/DNNL_ARG_SRC (extract src))
     (args* args 1 dnnl/DNNL_ARG_DST (extract dst))))
  ([src dst mean variance]
   (let-release [args (dnnl_exec_arg_t. 4)]
     (args* args 0 dnnl/DNNL_ARG_SRC (extract src))
     (args* args 1 dnnl/DNNL_ARG_DST (extract dst))
     (args* args 2 dnnl/DNNL_ARG_MEAN (extract mean))
     (args* args 3 dnnl/DNNL_ARG_VARIANCE (extract variance))))
  ([src dst scaleshift]
   (let-release [args (dnnl_exec_arg_t. 3)]
     (args* args 0 dnnl/DNNL_ARG_SRC (extract src))
     (args* args 1 dnnl/DNNL_ARG_DST (extract dst))
     (args* args 2 dnnl/DNNL_ARG_SCALE_SHIFT (extract scaleshift))))
  ([src dst scaleshift mean variance]
   (batch-norm-fwd-args src dst scaleshift mean variance nil))
  ([src dst scaleshift mean variance workspace]
   (let-release [args (dnnl_exec_arg_t. (if workspace 6 5))]
     (when workspace
       (args* args 5 dnnl/DNNL_ARG_WORKSPACE (extract workspace)))
     (args* args 0 dnnl/DNNL_ARG_SRC (extract src))
     (args* args 1 dnnl/DNNL_ARG_DST (extract dst))
     (args* args 2 dnnl/DNNL_ARG_SCALE_SHIFT (extract scaleshift))
     (args* args 3 dnnl/DNNL_ARG_MEAN (extract mean))
     (args* args 4 dnnl/DNNL_ARG_VARIANCE (extract variance)))))

(defn batch-norm-bwd-args
  ([diff-dst src mean variance diff-src]
   (let-release [args (dnnl_exec_arg_t. 5)]
     (args* args 0 dnnl/DNNL_ARG_DIFF_DST (extract diff-dst))
     (args* args 1 dnnl/DNNL_ARG_SRC (extract src))
     (args* args 2 dnnl/DNNL_ARG_MEAN (extract mean))
     (args* args 3 dnnl/DNNL_ARG_VARIANCE (extract variance))
     (args* args 4 dnnl/DNNL_ARG_DIFF_SRC (extract diff-src))))
  ([diff-dst src scaleshift mean variance diff-src diff-scaleshift]
   (batch-norm-bwd-args diff-dst src scaleshift mean variance diff-src diff-scaleshift nil))
  ([diff-dst src scaleshift mean variance diff-src diff-scaleshift workspace]
   (let-release [args (dnnl_exec_arg_t. (if workspace 8 7))]
     (when workspace
       (args* args 7 dnnl/DNNL_ARG_WORKSPACE (extract workspace)))
     (args* args 0 dnnl/DNNL_ARG_DIFF_DST (extract diff-dst))
     (args* args 1 dnnl/DNNL_ARG_SRC (extract src))
     (args* args 2 dnnl/DNNL_ARG_SCALE_SHIFT (extract scaleshift))
     (args* args 3 dnnl/DNNL_ARG_MEAN (extract mean))
     (args* args 4 dnnl/DNNL_ARG_VARIANCE (extract variance))
     (args* args 5 dnnl/DNNL_ARG_DIFF_SRC (extract diff-src))
     (args* args 6 dnnl/DNNL_ARG_DIFF_SCALE_SHIFT (extract diff-scaleshift)))))

;; ======================= Reduction ========================================================

(defn reduction-desc
  "TODO"
  ([alg-kind src-desc dst-desc p epsilon]
   (reduction-desc* (enc-keyword dnnl-reduction-alg-kind alg-kind)
                    (desc src-desc) (desc dst-desc) p epsilon))
  ([alg-kind src-desc dst-desc]
   (reduction-desc* (enc-keyword dnnl-reduction-alg-kind alg-kind)
                    (desc src-desc) (desc dst-desc) 0.0 0.0)))

;; ======================= Concat ============================================================

(defn concatenate
  "TODO"
  ([eng dst-desc concat-dimension & src-descs]
   (let [srcs (mapv desc src-descs)
         n (count srcs)]
     (let-release [s (dnnl_memory_desc_t. n)]
       (dotimes [i n]
         (.position s i)
         (.put s (srcs i)))
       (wrap (concat* (desc dst-desc) n concat-dimension s nil (extract eng)))))))

;; ======================== RNN ==============================================================

(defn vanilla-rnn-fwd-desc
  "TODO"
  ([prop-kind activation direction
    src-desc src-iter-desc weights-desc weights-iter-desc bias-desc dst-desc dst-iter-desc alpha beta]
   (vanilla-rnn-forward-desc* (enc-keyword dnnl-forward-prop-kind prop-kind)
                              (enc-keyword dnnl-eltwise-alg-kind activation)
                              (enc-keyword dnnl-direction direction)
                              (desc src-desc) (desc src-iter-desc)
                              (desc weights-desc) (desc weights-iter-desc) (desc bias-desc)
                              (desc dst-desc) (desc dst-iter-desc) alpha beta))
  ([prop-kind activation direction
    src-desc src-iter-desc weights-desc weights-iter-desc bias-desc dst-desc dst-iter-desc alpha]
   (vanilla-rnn-fwd-desc prop-kind activation direction src-desc src-iter-desc
                         weights-desc weights-iter-desc bias-desc dst-desc dst-iter-desc alpha 0.0))
  ([prop-kind activation direction
    src-desc src-iter-desc weights-desc weights-iter-desc bias-desc dst-desc dst-iter-desc]
   (vanilla-rnn-fwd-desc prop-kind activation direction src-desc src-iter-desc
                         weights-desc weights-iter-desc bias-desc dst-desc dst-iter-desc 0.0 0.0)))

(defn vanilla-rnn-bwd-desc
  "TODO"
  ([activation direction
    src-desc src-iter-desc weights-desc weights-iter-desc bias-desc dst-desc dst-iter-desc
    diff-src-desc diff-src-iter-desc diff-weights-desc diff-weights-iter-desc diff-bias-desc
    diff-dst-desc diff-dst-iter-desc alpha beta]
   (vanilla-rnn-backward-desc* (enc-keyword dnnl-eltwise-alg-kind activation)
                               (enc-keyword dnnl-direction direction)
                               (desc src-desc) (desc src-iter-desc)
                               (desc weights-desc) (desc weights-iter-desc) (desc bias-desc)
                               (desc dst-desc) (desc dst-iter-desc)
                               (desc diff-src-desc) (desc diff-src-iter-desc)
                               (desc diff-weights-desc) (desc diff-weights-iter-desc) (desc diff-bias-desc)
                               (desc diff-dst-desc) (desc diff-dst-iter-desc)
                               alpha beta))
  ([activation direction
    src-desc src-iter-desc weights-desc weights-iter-desc bias-desc dst-desc dst-iter-desc
    diff-src-desc diff-src-iter-desc diff-weights-desc diff-weights-iter-desc diff-bias-desc
    diff-dst-desc diff-dst-iter-desc alpha]
   (vanilla-rnn-bwd-desc activation direction src-desc src-iter-desc
                         weights-desc weights-iter-desc bias-desc dst-desc dst-iter-desc
                         diff-src-desc diff-src-iter-desc
                         diff-weights-desc diff-weights-iter-desc diff-bias-desc
                         diff-dst-desc diff-dst-iter-desc
                         alpha 0.0))
  ([activation direction
    src-desc src-iter-desc weights-desc weights-iter-desc bias-desc dst-desc dst-iter-desc
    diff-src-desc diff-src-iter-desc diff-weights-desc diff-weights-iter-desc diff-bias-desc
    diff-dst-desc diff-dst-iter-desc]
   (vanilla-rnn-bwd-desc activation direction src-desc src-iter-desc
                         weights-desc weights-iter-desc bias-desc dst-desc dst-iter-desc
                         diff-src-desc diff-src-iter-desc
                         diff-weights-desc diff-weights-iter-desc diff-bias-desc
                         diff-dst-desc diff-dst-iter-desc
                         0.0 0.0)))

;; ======================= LSTM ============================================================

(defn lstm-fwd-desc
  "TODO"
  ([prop-kind direction
    src-desc src-iter-desc src-iter-c-desc weights-desc weights-iter-desc
    bias-desc dst-desc dst-iter-desc dst-iter-c-desc]
   (lstm-forward-desc* (enc-keyword dnnl-forward-prop-kind prop-kind)
                       (enc-keyword dnnl-direction direction)
                       (desc src-desc) (desc src-iter-desc) (desc src-iter-c-desc)
                       (desc weights-desc) (desc weights-iter-desc) (desc bias-desc)
                       (desc dst-desc) (desc dst-iter-desc) (desc dst-iter-c-desc)))
  ([prop-kind direction
    src-desc src-iter-desc weights-desc weights-iter-desc
    bias-desc dst-desc dst-iter-desc]
   (lstm-fwd-desc prop-kind direction
                  src-desc src-iter-desc src-iter-desc weights-desc weights-iter-desc
                  bias-desc dst-desc dst-iter-desc dst-iter-desc)))

(defn lstm-bwd-desc
  "TODO"
  ([direction
    src-desc src-iter-desc src-iter-c-desc
    weights-desc weights-iter-desc bias-desc
    dst-desc dst-iter-desc dst-iter-c-desc
    diff-src-desc diff-src-iter-desc diff-src-iter-c-desc
    diff-weights-desc diff-weights-iter-desc diff-bias-desc
    diff-dst-desc diff-dst-iter-desc diff-dst-iter-c-desc]
   (lstm-backward-desc* (enc-keyword dnnl-direction direction)
                        (desc src-desc) (desc src-iter-desc) (desc src-iter-c-desc)
                        (desc weights-desc) (desc weights-iter-desc) (desc bias-desc)
                        (desc dst-desc) (desc dst-iter-desc) (desc dst-iter-c-desc)
                        (desc diff-src-desc) (desc diff-src-iter-desc) (desc diff-src-iter-c-desc)
                        (desc diff-weights-desc) (desc diff-weights-iter-desc) (desc diff-bias-desc)
                        (desc diff-dst-desc) (desc diff-dst-iter-desc) (desc diff-dst-iter-c-desc)))
  ([direction
    src-desc src-iter-desc
    weights-desc weights-iter-desc bias-desc
    dst-desc dst-iter-desc
    diff-src-desc diff-src-iter-desc
    diff-weights-desc diff-weights-iter-desc diff-bias-desc
    diff-dst-desc diff-dst-iter-desc]
   (lstm-bwd-desc direction
                  src-desc src-iter-desc src-iter-desc weights-desc weights-iter-desc bias-desc
                  dst-desc dst-iter-desc dst-iter-desc
                  diff-src-desc diff-src-iter-desc diff-src-iter-desc
                  diff-weights-desc diff-weights-iter-desc diff-bias-desc
                  diff-dst-desc diff-dst-iter-desc diff-dst-iter-desc)))

;; ================================= GRU ====================================================

(defn gru-fwd-desc
  "TODO"
  [prop-kind direction
   src-desc src-iter-desc weights-desc weights-iter-desc
   bias-desc dst-desc dst-iter-desc]
  (gru-forward-desc* (enc-keyword dnnl-forward-prop-kind prop-kind)
                     (enc-keyword dnnl-direction direction)
                     (desc src-desc) (desc src-iter-desc)
                     (desc weights-desc) (desc weights-iter-desc) (desc bias-desc)
                     (desc dst-desc) (desc dst-iter-desc)))

(defn gru-bwd-desc
  "TODO"
  [direction
   src-desc src-iter-desc weights-desc weights-iter-desc bias-desc
   dst-desc dst-iter-desc
   diff-src-desc diff-src-iter-desc
   diff-weights-desc diff-weights-iter-desc diff-bias-desc
   diff-dst-desc diff-dst-iter-desc]
  (gru-backward-desc* (enc-keyword dnnl-direction direction)
                      (desc src-desc) (desc src-iter-desc)
                      (desc weights-desc) (desc weights-iter-desc) (desc bias-desc)
                      (desc dst-desc) (desc dst-iter-desc)
                      (desc diff-src-desc) (desc diff-src-iter-desc)
                      (desc diff-weights-desc) (desc diff-weights-iter-desc) (desc diff-bias-desc)
                      (desc diff-dst-desc) (desc diff-dst-iter-desc)))
