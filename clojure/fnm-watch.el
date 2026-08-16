;;; fnm-watch.el --- A pinned, self-overwriting watch buffer -*- lexical-binding: t -*-

;;; Commentary:

;; Polls `fnm.watch/render' on a timer and replaces the buffer contents in
;; place.  Unlike `cider-tap', nothing is appended -- the buffer always shows
;; the current frame's snapshot and nothing else.
;;
;; Usage:  M-x fnm-watch   /   M-x fnm-watch-stop
;;
;; Load with:  (load "/home/joe/Development/fnm/clojure/fnm-watch.el")

;;; Code:

(require 'cider-client)

(defvar fnm-watch-buffer "*fnm-watch*")
(defvar fnm-watch-interval 0.2
  "Seconds between polls.  This is the watch rate, not the frame rate.")

(defvar fnm-watch--timer nil)

(defun fnm-watch--paint (text)
  "Replace the watch buffer's contents with TEXT."
  (when-let* ((buf (get-buffer fnm-watch-buffer)))
    (let ((tmp (get-buffer-create " *fnm-watch-src*")))
      (with-current-buffer tmp
        (erase-buffer)
        (insert text))
      (with-current-buffer buf
        (let ((inhibit-read-only t))
          ;; replace-buffer-contents diffs rather than erasing, so point and
          ;; scroll position survive every tick.  erase-buffer + insert would
          ;; yank the cursor back to the top five times a second.
          (replace-buffer-contents tmp))))))

(defun fnm-watch--tick ()
  "Poll the snapshot once, asynchronously."
  (if (not (get-buffer fnm-watch-buffer))
      (fnm-watch-stop)
    ;; Async, not `cider-nrepl-sync-request': a sync request on a timer blocks
    ;; Emacs's UI thread every tick.
    (cider-nrepl-request:eval
     "(fnm.watch/render)"
     (lambda (response)
       (nrepl-dbind-response response (value err)
         (cond
          (err   (fnm-watch--paint (format "error:\n%s" err)))
          (value (fnm-watch--paint (car (read-from-string value))))))))))

(define-derived-mode fnm-watch-mode special-mode "fnm-watch"
  "Major mode for the pinned watch buffer."
  (setq-local truncate-lines t))

;;;###autoload
(defun fnm-watch ()
  "Open the pinned watch buffer and start polling."
  (interactive)
  (cider-current-repl nil 'ensure)
  (with-current-buffer (get-buffer-create fnm-watch-buffer)
    (unless (eq major-mode 'fnm-watch-mode)
      (fnm-watch-mode)))
  (when fnm-watch--timer (cancel-timer fnm-watch--timer))
  (setq fnm-watch--timer
        (run-with-timer 0 fnm-watch-interval #'fnm-watch--tick))
  (display-buffer fnm-watch-buffer))

(defun fnm-watch-stop ()
  "Stop polling."
  (interactive)
  (when fnm-watch--timer
    (cancel-timer fnm-watch--timer)
    (setq fnm-watch--timer nil)))

(provide 'fnm-watch)
;;; fnm-watch.el ends here
