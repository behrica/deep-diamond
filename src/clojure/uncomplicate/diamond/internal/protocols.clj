(ns uncomplicate.diamond.internal.protocols)

(declare default-diamond-factory)

;; ===================== General ========================================

(defprotocol DiamondFactoryProvider
  (diamond-factory [_])
  (native-diamond-factory [_]))

(defprotocol NeanderthalFactoryProvider
  (neanderthal-factory [this dtype]))

;; ===================== Tensor ========================================

(defprotocol TensorFactory
  (create-tensor-desc [this desc] [this shape type format])
  (create-tensor [this desc init])
  (create-transformer [this in out])
  (create-batcher [this src dst mb-size])
  (create-shuffler [this src dst])
  (tensor-engine [this tdesc]))

(defprotocol Offset
  (offset [tz n-ofst]))

;; =================== DNN ============================================

(defprotocol DnnFactory
  (activ-blueprint [this src-desc activ alpha beta])
  (inner-product-blueprint [this src-desc dst-desc weights-type])
  (fc-blueprint [this src-desc dst-desc activ alpha beta weights-type])
  (convolution-blueprint [this src-desc kernel-desc dst-desc activ
                          strides padding dilation alpha beta])
  (pooling-blueprint [this src-desc dst-desc algo strides kernel padding])
  (gaussian-dropout-blueprint [this src-desc sd])
  (batch-norm-blueprint [this src-desc activ alpha beta])
  (concat-blueprint [this src-descs conc-dim dst-type])
  (branch-blueprint [this src-desc split-dim dst-descs])
  (split-blueprint [this src-desc n])
  (sum-blueprint [this src-descs])
  (create-workspace [this byte-size]))

(defprotocol CostFactory
  (quadratic-cost [this last-layer train-tz])
  (mean-absolute-cost [this last-layer train-tz])
  (crossentropy-cost [this last-layer train-tz]))

(defprotocol DescriptorProvider
  (inf-desc [this])
  (train-desc [this]))

(defprotocol Parameters
  (weights [this])
  (bias [this]))

(defprotocol ParametersSeq
  (parameters [this]))

(defprotocol DiffParameters
  (diff-weights [this]))

(defprotocol Initializable
  (init [this init-fn]))

(defprotocol DiffTransfer
  (diff-input [this])
  (diff-z [this])
  (diff-output [this]))

(defprotocol Backprop
  (forward [this] [this hyperparam])
  (backward [this] [this hyperparam]))

(defprotocol LinearBackprop
  (backward-diff [this scal-diff-w scal-g scal-diff-b scal-b]))

(defprotocol Workspace
  (inf-ws-size [this])
  (train-ws-size [this]))

(def ^:dynamic *workspace* nil)
