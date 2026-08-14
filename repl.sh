#!/usr/bin/env bash
# Start the fnm dev REPL: a jank terminal REPL + nREPL server, with src/ on the
# module path so `(require 'fnm.core)` works. --network=host makes the printed
# nrepl://127.0.0.1:PORT reachable from a host editor (Conjure/CIDER) too.
#
#   ./repl.sh
#   user=> (require 'fnm.core)
#   user=> (fnm.core/-main)
#
# Build the image first:
#   podman build --no-cache --pull -t fnm-jank:latest -f Containerfile .
set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

tty_flags=()
[ -t 0 ] && [ -t 1 ] && tty_flags=(-it)

exec podman run --rm "${tty_flags[@]}" \
  --network=host \
  -v "$REPO":/work:Z \
  -v fnm-jank-cache:/root/.cache/jank \
  -w /work \
  fnm-jank:latest \
  jank --module-path /work/src repl
