(ns fnm.sand
  (:require [fnm.rl :as rl]
            [fnm.watch :as w])
  (:import (java.lang.foreign MemorySegment ValueLayout)))

(set! *warn-on-reflection* true)
(set! *unchecked-math* :warn-on-boxed)

(def ^:const screen-width 1900)
(def ^:const screen-height 1200)
(def ^:const cell-size 5)
(def ^:const rows (/ screen-height cell-size))
(def ^:const cols (/ screen-width cell-size))
(def ^:const cell-count (* rows cols))
(def ^:const gravity 0.1)
(def ^:const brush 20)

;; One int per palette entry, in native RGBA8888 byte order, written straight
;; into the pixel buffer.
(def palette
  (int-array (map rl/rgba-le [0xE6B800 0x3B6E8C 0xA83232 0xCC6B1F])))

(defonce grid (int-array cell-count))
(defonce vel (float-array cell-count))
(defonce state (atom {:color-idx 0}))

(defonce px (rl/alloc-pixels cell-count))
(defonce gpu (atom nil))

(defn init-gpu!
  "Needs a GL context, so this runs after init-window!."
  []
  (let [tex (rl/load-texture-from-image
              {:data px :width cols :height rows
               :mipmaps 1 :format rl/pixelformat-r8g8b8a8})]
    (rl/set-texture-filter! tex rl/texture-filter-point)
    (reset! gpu {:tex    (rl/texture-seg tex)
                 :src    (rl/rect-seg 0 0 cols rows)
                 :dst    (rl/rect-seg 0 0 screen-width screen-height)
                 :origin (rl/vec2-seg 0 0)})))

(defn idx ^long [^long row ^long col]
  (+ (* row cols) col))

(defn clear-grid! []
  (java.util.Arrays/fill ^ints grid (int 0))
  (java.util.Arrays/fill ^floats vel (float 0)))

(defn step-cell [^ints g ^floats v ^long row ^long col]
  (let [i (idx row col)
        c (aget g i)
        current-velocity (aget v i)]
    (when (and (not (zero? c))
               (not (zero? current-velocity))
               (< row (dec rows)))
      (let [vv     (+ current-velocity gravity)
            target (min (dec rows) (+ row (long vv)))]
        (loop [y target]
          (if (<= y row)
            (aset v i (float 0))
            (let [j (idx y col)]
              (if (zero? (aget g j))
                (do (aset g j c)
                    (aset g i (int 0))
                    (aset v i (float 0))
                    (aset v j (float vv)))
                (let [can-left  (and (> col 0) (zero? (aget g (dec j))))
                      can-right (and (< col (dec cols)) (zero? (aget g (inc j))))
                      side      (long
                                  (cond (and can-left (not can-right)) -1
                                        (and can-right (not can-left)) 1
                                        can-left (if (zero? (.nextInt (java.util.concurrent.ThreadLocalRandom/current) 2)) 1 -1)
                                        :else 0))]
                  (if (or can-left can-right)
                    (do (aset g (+ j side) c)
                        (aset g i (int 0))
                        (aset v i (float 0))
                        (aset v (+ j side) (float vv)))
                    (recur (dec y))))))))))))

(defn physics []
  (let [^ints g grid
        ^floats v vel]
    (loop [row (dec rows)]
      (when (>= row 0)
        (dotimes [col cols]
          (step-cell g v row col))
        (recur (dec row))))))

(defn paint! []
  (let [^ints g   grid
        ^floats v vel
        m    (rl/get-mouse-position)
        row  (quot (long (:y m)) cell-size)
        col  (quot (long (:x m)) cell-size)
        half (long (quot brush 2))
        ci   (int (inc (long (:color-idx @state))))]
    (dotimes [x brush]
      (dotimes [y brush]
        (let [nr (+ row (- (long y) half))
              nc (+ col (- (long x) half))]
          (when (and (>= nr 0) (< nr (dec rows))
                     (>= nc 0) (< nc (dec cols)))
            (let [i (idx nr nc)]
              (when (and (zero? (aget g i)) (zero? (.nextInt (java.util.concurrent.ThreadLocalRandom/current) 2)))
                (aset g i ci)
                (aset v i (float 1.0))))))))))

(defn handle-input! []
  (when (rl/key-pressed? rl/key-r)
    (clear-grid!))
  (when (rl/mouse-button-down? rl/mouse-button-left)
    (paint!))
  (when (rl/mouse-button-released? rl/mouse-button-left)
    (swap! state update :color-idx #(rem (inc (long %)) (alength ^ints palette)))))

(defn draw []
  (let [^ints g   grid
        ^ints pal palette
        ^MemorySegment buf px
        {:keys [tex src dst origin]} @gpu]
    (dotimes [i cell-count]
      (let [c (aget g i)]
        (.setAtIndex buf ValueLayout/JAVA_INT i
                     (if (zero? c) (int 0) (aget pal (dec c))))))
    (rl/clear-background!* rl/black)
    (rl/update-texture!* tex buf)
    (rl/draw-texture-pro!* tex src dst origin (float 0.0) rl/white)
    (rl/draw-fps 20 20)))

(defonce last-error (atom nil))

(defn frame []
  (handle-input!)
  (physics)
  (rl/begin-drawing!)
  (try (draw)
       (finally (rl/end-drawing!)))
  (w/watch! {:mouse     (rl/get-mouse-position)
             :color-idx (:color-idx @state)
             :error     @last-error}))

(defn -main [& _args]
  (rl/set-trace-log-level! rl/log-warning)
  (rl/init-window! screen-width screen-height "SAND")
  (rl/set-target-fps! 120)
  (init-gpu!)
  (while (not (rl/window-should-close?))
    (try (frame)
         (catch Throwable t (reset! last-error t))))
  (rl/close-window!))

(comment
  (future (-main))
  (clear-grid!)
  ,)
