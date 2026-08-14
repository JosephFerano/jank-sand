#!/usr/bin/env bash
# Run jank inside the Ubuntu container, with this project mounted at /work.
# The prebuilt jank only JITs cleanly on Ubuntu, so we don't run it on the host.
#
#   ./jank.sh check-health
#   ./jank.sh --module-path /work/src run /work/src/fnm/core.jank
#   ./jank.sh repl
#
# Build the image first:
#   podman build --no-cache --pull -t fnm-jank:latest -f Containerfile .
set -euo pipefail

# This project's root IS the script's directory (unlike fide, where the jank
# code lives in a jank-version/ subdir of a larger repo).
REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# fnm-jank-cache persists the compiled prelude PCH across runs, so only the first
# invocation pays the "Building pre-compiled header" cost. Kept separate from
# fide's jank-cache: the PCH dir is a hash of compiler flags, not of the jank
# build, so two different jank binaries with the same flags would collide.
# Only allocate a TTY when we actually have one, so piping/CI stays clean.
tty_flags=()
[ -t 0 ] && [ -t 1 ] && tty_flags=(-it)

exec podman run --rm "${tty_flags[@]}" \
  -v "$REPO":/work:Z \
  -v fnm-jank-cache:/root/.cache/jank \
  -w /work \
  fnm-jank:latest \
  jank "$@"
