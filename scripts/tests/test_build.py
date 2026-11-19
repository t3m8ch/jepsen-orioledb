"""Check temporary export cleanup without invoking Podman."""
import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).resolve().parents[1] / "build.sh"


class BuildTests(unittest.TestCase):
    def test_exports_are_cleaned_and_previous_artifact_survives_failure(self):
        for outcome in ("build-error", "checksum-error", "success"):
            with self.subTest(outcome=outcome), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                (root / "scripts").mkdir()
                shutil.copyfile(SCRIPT, root / "scripts/build.sh")
                current = root / ".cache/artifacts/current"
                current.mkdir(parents=True)
                (current / "previous").write_text("keep on failure")
                binary = root / "bin"
                binary.mkdir()
                podman = binary / "podman"
                podman.write_text('''#!/usr/bin/env bash
set -eu
for arg in "$@"; do
  case "$arg" in type=local,dest=*) output=${arg#type=local,dest=} ;; esac
done
echo "stub build log"
printf '%s\n' "$@"
printf new > "$output/pgsql.tar.gz"
if [[ $OUTCOME == build-error ]]; then exit 7; fi
(cd "$output" && sha256sum pgsql.tar.gz > SHA256SUMS)
if [[ $OUTCOME == checksum-error ]]; then printf corrupt >> "$output/pgsql.tar.gz"; fi
''')
                podman.chmod(0o755)
                result = subprocess.run(
                    ["bash", "scripts/build.sh"] + (["--trace-evictions"] if outcome == "success" else []),
                    cwd=root, capture_output=True, text=True,
                    env=dict(os.environ, PATH=str(binary) + os.pathsep + os.environ["PATH"],
                             OUTCOME=outcome),
                )
                self.assertEqual(list(current.parent.glob(".export-*")), [])
                log = (root / ".cache/build.log").read_text()
                self.assertIn("stub build log", log)
                self.assertIn(f"LAB_TRACE_EVICTIONS={int(outcome == 'success')}\n", log)
                if outcome == "success":
                    self.assertEqual(result.returncode, 0, result.stderr)
                    self.assertEqual((current / "pgsql.tar.gz").read_text(), "new")
                    self.assertFalse((current / "previous").exists())
                else:
                    self.assertNotEqual(result.returncode, 0)
                    self.assertEqual((current / "previous").read_text(), "keep on failure")
