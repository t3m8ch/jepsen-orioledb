#!/usr/bin/env bash
set -euo pipefail

# LazyFS 0.3.0, the version used by jepsen.lazyfs in Jepsen 0.3.15.
commit=9c5685e828da8b5d113d2f9134f6671e481d7162
prefix=/opt/jepsen/lazyfs

export DEBIAN_FRONTEND=noninteractive
apt-get -o Acquire::Retries=3 install -y --no-install-recommends \
  build-essential cmake git ca-certificates fuse3 libfuse3-dev
[[ -c /dev/fuse ]] || modprobe fuse
sed -i -E 's/^#\s*(user_allow_other)\s*$/\1/' /etc/fuse.conf

if [[ ! -d $prefix/.git ]]; then
  git clone https://github.com/dsrhaslab/lazyfs.git "$prefix"
fi
git -C "$prefix" checkout --detach "$commit"
cmake -S "$prefix/libs/libpcache" -B "$prefix/libs/libpcache/build"
cmake --build "$prefix/libs/libpcache/build" --parallel 2
cmake -S "$prefix/lazyfs" -B "$prefix/lazyfs/build" -DLAZYFS_BUILD_TESTS=OFF
cmake --build "$prefix/lazyfs/build" --parallel 2
