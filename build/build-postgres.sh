#!/usr/bin/env bash
set -euo pipefail

: "${LAB_PG_CONFIGURE:?}" "${LAB_CFLAGS:?}"
prefix=/opt/jepsen/pgsql
jobs=${LAB_BUILD_JOBS:-4}

cd /work/postgres
read -r -a configure_flags <<<"$LAB_PG_CONFIGURE"
./configure --prefix="$prefix" "${configure_flags[@]}" CFLAGS="$LAB_CFLAGS"
make -j "$jobs"
make install
make -C contrib -j "$jobs" install
