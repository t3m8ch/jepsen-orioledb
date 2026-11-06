#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."
mkdir -p .cache/artifacts
output=$(mktemp -d "$PWD/.cache/artifacts/.export-XXXXXXXX")

# Podman reuses cached layers and exports only the final stage's files.
podman build --layers --force-rm --pull=missing --tag localhost/jepsen-orioledb:build \
  --build-arg LAB_DEBIAN_IMAGE \
  --build-arg LAB_DEBIAN_RELEASE \
  --build-arg LAB_APT_SNAPSHOT \
  --build-arg LAB_BUILD_PACKAGES \
  --build-arg LAB_RUNTIME_PACKAGES \
  --build-arg LAB_POSTGRES_SHA \
  --build-arg LAB_ORIOLEDB_SHA \
  --build-arg LAB_DEBIAN_VERSION \
  --build-arg LAB_PG_CONFIGURE \
  --build-arg LAB_CFLAGS \
  --output "type=local,dest=$output" build 2>&1 | tee .cache/build.log

# Replace the previous export only after a successful build and verification.
(cd "$output" && sha256sum --check SHA256SUMS)
rm -rf .cache/artifacts/current
mv "$output" .cache/artifacts/current

echo "Build exported to .cache/artifacts/current"
