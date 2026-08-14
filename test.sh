#!/usr/bin/env bash
# Run the fnm test suites in the container. Exits nonzero if anything fails.
#
# Build the image first:
#   podman build --no-cache --pull -t fnm-jank:latest -f Containerfile .

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
fail=0

echo "== core tests (jank) =="
podman run --rm \
  -v "$REPO":/work:Z -v fnm-jank-cache:/root/.cache/jank -w /work \
  fnm-jank:latest \
  jank --module-path /work/src \
       run /work/test/core_test.jank || fail=1

echo
[ "$fail" = 0 ] && echo "ALL SUITES GREEN" || echo "SOME SUITES FAILED"
exit "$fail"
