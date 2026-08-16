(ns fnm.rl
  "Hand-written raylib bindings via coffi/Panama. Only what sand needs.

   Struct layouts are written against raylib 6.0's raylib.h -- a mismatch here is
   silent memory corruption, not an error, so check src/raylib.h before bumping."
  (:require
   [coffi.mem :as mem :refer [defalias]]
   [coffi.ffi :as ffi :refer [defcfn]])
  (:import
   (java.lang.foreign Arena MemorySegment)))

(ffi/load-library (or (System/getProperty "raylib.path")
                      "libraylib.so.600"))

;;; ---------------------------------------------------------------- primitives
;; coffi's ::mem/byte is signed; raylib's Color fields are unsigned char, so 230
;; would overflow on the way in. Round-trip through unchecked-byte instead.

(defmethod mem/primitive-type ::ubyte [_type] ::mem/byte)
(defmethod mem/serialize* ::ubyte [obj _type _scope] (unchecked-byte obj))
(defmethod mem/deserialize* ::ubyte [obj _type] (Byte/toUnsignedLong obj))

;; C bool is one byte.
(defmethod mem/primitive-type ::bool [_type] ::mem/byte)
(defmethod mem/serialize* ::bool [obj _type _scope] (byte (if obj 1 0)))
(defmethod mem/deserialize* ::bool [obj _type] (not (zero? obj)))

;;; ------------------------------------------------------------------- structs

(defalias ::color
  [::mem/struct [[:r ::ubyte] [:g ::ubyte] [:b ::ubyte] [:a ::ubyte]]])

(defalias ::vector-2
  [::mem/struct [[:x ::mem/float] [:y ::mem/float]]])

(defalias ::rectangle
  [::mem/struct [[:x ::mem/float] [:y ::mem/float]
                 [:width ::mem/float] [:height ::mem/float]]])

(defalias ::texture
  [::mem/struct [[:id ::mem/int] [:width ::mem/int] [:height ::mem/int]
                 [:mipmaps ::mem/int] [:format ::mem/int]]])

(defalias ::image
  [::mem/struct [[:data ::mem/pointer] [:width ::mem/int] [:height ::mem/int]
                 [:mipmaps ::mem/int] [:format ::mem/int]]])

;;; ----------------------------------------------------------------- constants

(def ^:const key-r 82)
(def ^:const mouse-button-left 0)
(def ^:const log-warning 4)
(def ^:const pixelformat-r8g8b8a8 7)
(def ^:const texture-filter-point 0)

;;; ----------------------------------------------------------------- functions

(defcfn init-window!    "InitWindow"  [::mem/int ::mem/int ::mem/c-string] ::mem/void)
(defcfn close-window!   "CloseWindow" [] ::mem/void)
(defcfn set-target-fps! "SetTargetFPS" [::mem/int] ::mem/void)
(defcfn set-trace-log-level! "SetTraceLogLevel" [::mem/int] ::mem/void)
(defcfn window-should-close? "WindowShouldClose" [] ::bool)

(defcfn begin-drawing! "BeginDrawing" [] ::mem/void)
(defcfn end-drawing!   "EndDrawing"   [] ::mem/void)

(defcfn draw-fps       "DrawFPS"      [::mem/int ::mem/int] ::mem/void)
(defcfn get-fps        "GetFPS"       [] ::mem/int)

(defcfn key-pressed?           "IsKeyPressed"          [::mem/int] ::bool)
(defcfn mouse-button-down?     "IsMouseButtonDown"     [::mem/int] ::bool)
(defcfn mouse-button-released? "IsMouseButtonReleased" [::mem/int] ::bool)
(defcfn get-mouse-position     "GetMousePosition"      [] ::vector-2)

;; The !* forms take pre-serialized struct segments and allocate nothing per
;; call. Everything in a per-frame path uses these.
(def clear-background!*
  (ffi/make-downcall "ClearBackground" [::color] ::mem/void))

(def draw-rectangle!*
  (ffi/make-downcall "DrawRectangle"
                     [::mem/int ::mem/int ::mem/int ::mem/int ::color] ::mem/void))

(def update-texture!*
  (ffi/make-downcall "UpdateTexture" [::texture ::mem/pointer] ::mem/void))

(def draw-texture-pro!*
  (ffi/make-downcall "DrawTexturePro"
                     [::texture ::rectangle ::rectangle ::vector-2 ::mem/float ::color]
                     ::mem/void))

;; Called once at startup, so the map-taking form is fine.
(defcfn load-texture-from-image "LoadTextureFromImage" [::image] ::texture)
(defcfn unload-texture!         "UnloadTexture"        [::texture] ::mem/void)
(defcfn set-texture-filter!     "SetTextureFilter"     [::texture ::mem/int] ::mem/void)

;;; --------------------------------------------------- native value allocation

(defonce ^Arena arena (Arena/ofAuto))

(defn color-seg
  "Serialize a packed 0xRRGGBB int into a reusable native Color, once."
  [^long hex]
  (mem/serialize {:r (bit-and (bit-shift-right hex 16) 0xFF)
                  :g (bit-and (bit-shift-right hex 8) 0xFF)
                  :b (bit-and hex 0xFF)
                  :a 255}
                 ::color arena))

(def black (color-seg 0x000000))
(def white (color-seg 0xFFFFFF))

(defn rect-seg [x y w h]
  (mem/serialize {:x (float x) :y (float y) :width (float w) :height (float h)}
                 ::rectangle arena))

(defn vec2-seg [x y]
  (mem/serialize {:x (float x) :y (float y)} ::vector-2 arena))

(defn texture-seg [tex]
  (mem/serialize tex ::texture arena))

(defn alloc-pixels
  "An RGBA8888 pixel buffer, native so UpdateTexture can read it directly."
  ^MemorySegment [^long n-pixels]
  (.allocate arena (* 4 n-pixels) 4))

(defn rgba-le
  "0xRRGGBB -> an int whose little-endian bytes are R,G,B,A, matching
   PIXELFORMAT_UNCOMPRESSED_R8G8B8A8 in memory."
  ^long [^long hex]
  (unchecked-int
   (bit-or (bit-and (bit-shift-right hex 16) 0xFF)
           (bit-shift-left (bit-and (bit-shift-right hex 8) 0xFF) 8)
           (bit-shift-left (bit-and hex 0xFF) 16)
           (bit-shift-left 0xFF 24))))
