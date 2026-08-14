#!/usr/bin/env bash
# Run a jank file that uses raylib, with the host X server forwarded in.
#
#   ./raylib.sh run-main fnm.raylib-hello
#
# No xhost needed: rootless podman maps container-root to your uid, so the X
# server sees the client as you. --device /dev/dri gives the container the GPU;
# without it raylib has no GL context. If GL still fails, set
# LIBGL_ALWAYS_SOFTWARE=1 to fall back to llvmpipe (slow software rendering).
set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

tty_flags=()
[ -t 0 ] && [ -t 1 ] && tty_flags=(-it)

dri_flags=()
[ -d /dev/dri ] && dri_flags=(--device /dev/dri)

exec podman run --rm "${tty_flags[@]}" \
  --net=host \
  -e DISPLAY \
  -e LIBGL_ALWAYS_SOFTWARE="${LIBGL_ALWAYS_SOFTWARE:-}" \
  -v /tmp/.X11-unix:/tmp/.X11-unix \
  "${dri_flags[@]}" \
  --security-opt label=disable \
  -v "$REPO":/work \
  -v fnm-jank-cache:/root/.cache/jank \
  -w /work \
  fnm-jank:latest \
  jank -I/usr/local/include -L/usr/local/lib -L/usr/lib/x86_64-linux-gnu \
       -l raylib -l GL -l m \
       -l X11 -l Xrandr -l Xinerama -l Xcursor -l Xi \
       --module-path /work/src "$@"
