#!/usr/bin/env bash
set -euo pipefail

: "${LAB_POSTGRES_SHA:?}" "${LAB_ORIOLEDB_SHA:?}"
: "${LAB_PG_CONFIGURE:?}" "${LAB_CFLAGS:?}" "${LAB_RUNTIME_PACKAGES:?}"
[[ $(dpkg --print-architecture) == amd64 ]]

prefix=/opt/jepsen/pgsql
pgdata=/work/smoke-data
jobs=${LAB_BUILD_JOBS:-4}

cleanup() {
  if [[ -s $pgdata/postmaster.pid ]]; then
    "$prefix/bin/pg_ctl" -D "$pgdata" -m immediate -w stop || true
  fi
}

trap cleanup EXIT

expected=$(awk -F': ' '/^17: / {print $2}' /work/orioledb/.pgtags)
if [[ $expected != "$LAB_POSTGRES_SHA" ]]; then
  echo 'PostgreSQL SHA differs from OrioleDB .pgtags' >&2
  exit 1
fi

cd /work/postgres
read -r -a configure_flags <<<"$LAB_PG_CONFIGURE"
./configure --prefix="$prefix" "${configure_flags[@]}" CFLAGS="$LAB_CFLAGS"
make -j "$jobs"
make install
make -C contrib -j "$jobs" install

cd /work/orioledb
make -j "$jobs" USE_PGXS=1 IS_DEV=1 PG_CONFIG="$prefix/bin/pg_config"
make USE_PGXS=1 IS_DEV=1 PG_CONFIG="$prefix/bin/pg_config" install

# Fail for any missing shared library, including the extension's dependencies.
ldd "$prefix/bin/postgres" "$prefix/lib/orioledb.so" >/out/ldd.txt
if grep -q 'not found' /out/ldd.txt; then
  cat /out/ldd.txt >&2
  exit 1
fi

# Smoke test

"$prefix/bin/initdb" -D "$pgdata" --locale=C --encoding=UTF8
cat >>"$pgdata/postgresql.conf" <<'CONF'
shared_preload_libraries = 'orioledb'
listen_addresses = ''
unix_socket_directories = '/work'
CONF

"$prefix/bin/pg_ctl" -D "$pgdata" -l /out/smoke.log -w start
"$prefix/bin/psql" -h /work -d postgres -v ON_ERROR_STOP=1 <<'SQL'
CREATE EXTENSION orioledb;
CREATE TABLE smoke (id int PRIMARY KEY, value text) USING orioledb;
INSERT INTO smoke VALUES (1, 'ok');
SELECT * FROM smoke;
SQL

"$prefix/bin/pg_ctl" -D "$pgdata" -m fast -w stop
"$prefix/bin/pg_ctl" -D "$pgdata" -l /out/smoke.log -w start
[[ $("$prefix/bin/psql" -h /work -d postgres -Atc 'SELECT value FROM smoke WHERE id = 1') == ok ]]
"$prefix/bin/pg_ctl" -D "$pgdata" -m fast -w stop

# Build facts travel with the installation and are collected through LogFiles.
packages='{}'
for name in $LAB_RUNTIME_PACKAGES; do
  version=$(dpkg-query -W -f='${Version}' "$name")
  packages=$(jq --arg name "$name" --arg version "$version" '. + {($name): $version}' <<<"$packages")
done

jq -n --argjson packages "$packages" --arg configure "$("$prefix/bin/pg_config" --configure)" '
  {postgres_sha: env.LAB_POSTGRES_SHA,
   orioledb_sha: env.LAB_ORIOLEDB_SHA, debian_image: env.LAB_DEBIAN_IMAGE,
   debian_version: env.LAB_DEBIAN_VERSION, apt_snapshot: env.LAB_APT_SNAPSHOT,
   pg_configure: env.LAB_PG_CONFIGURE, cflags: env.LAB_CFLAGS,
   architecture: "amd64", runtime_packages: $packages, pg_config: $configure}
' >"$prefix/build-info.json"
cp "$prefix/build-info.json" /out/build-info.json

# Deterministic archive metadata; byte-for-byte compiler reproducibility is not claimed.
tar --sort=name --mtime=@0 --owner=0 --group=0 --numeric-owner -C "$prefix" -cf - . | gzip -n >/out/pgsql.tar.gz
(cd /out && sha256sum pgsql.tar.gz build-info.json ldd.txt smoke.log >SHA256SUMS)
