# Ubuntu 24.04 image with jank installed from the official apt repo.
# The prebuilt jank binary bakes in Ubuntu include paths, so it only JITs
# cleanly on Ubuntu — hence we run it here instead of on the Fedora host.
#
# Tagged fnm-jank:latest, deliberately separate from the fide project's
# jank:latest, so rebuilding this never disturbs fide's pinned render golden.
#
# Build:  podman build --no-cache --pull -t fnm-jank:latest -f Containerfile .
# Run:    ./jank.sh <jank args>     (mounts this project at /work)
#
# NOTE: the PPA always calls the package "0.1" regardless of build date, so a
# cached build silently yields the old binary. Always pass --no-cache --pull.
#
# NOTE: do NOT fetch https://ppa.jank-lang.org/jank.list (as fide's Containerfile
# does). The PPA moved to a dists/ layout, but that file still serves the old
# flat "https://ppa.jank-lang.org ./" line, so apt-get update 404s on Release.
# We write the source line ourselves, per the current upstream install docs.
FROM ubuntu:24.04

ENV DEBIAN_FRONTEND=noninteractive

RUN apt-get update \
 && apt-get install -y --no-install-recommends \
      curl ca-certificates gnupg lsb-release \
 && curl -fsSL "https://ppa.jank-lang.org/KEY.gpg" \
      | gpg --dearmor -o /etc/apt/trusted.gpg.d/jank.gpg \
 && echo "deb [signed-by=/etc/apt/trusted.gpg.d/jank.gpg] https://ppa.jank-lang.org $(lsb_release -cs) main" \
      > /etc/apt/sources.list.d/jank.list \
 && apt-get update \
 && apt-get install -y --no-install-recommends jank \
 && rm -rf /var/lib/apt/lists/*

# raylib 6.0 (latest tag), built from source: it isn't in Ubuntu 24.04 apt.
# The X11 -dev libs are load-bearing — without them CMake still configures and
# builds fine, but the result can't create a window at runtime.
RUN apt-get update \
 && apt-get install -y --no-install-recommends \
      git cmake build-essential \
      libgl1-mesa-dev libx11-dev libxrandr-dev libxinerama-dev \
      libxcursor-dev libxi-dev \
 && git clone --depth 1 -b 6.0 https://github.com/raysan5/raylib /tmp/raylib \
 && cmake -S /tmp/raylib -B /tmp/raylib/build \
      -DCMAKE_BUILD_TYPE=Release \
      -DBUILD_SHARED_LIBS=OFF \
      -DBUILD_EXAMPLES=OFF \
 && cmake --build /tmp/raylib/build -j"$(nproc)" \
 && cmake --install /tmp/raylib/build \
 && ldconfig \
 && rm -rf /tmp/raylib /var/lib/apt/lists/*

WORKDIR /work
CMD ["jank", "check-health"]
