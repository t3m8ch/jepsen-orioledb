"""Exercise the actual VM collector with a controllable psql process."""
import json
import os
from pathlib import Path
import signal
import subprocess
import tempfile
import time
import unittest

SCRIPT = Path(__file__).resolve().parents[2] / "jepsen/resources/page-stats.sh"


class PageStatsTest(unittest.TestCase):
    def test_collector_recovers_and_shutdown_is_bounded(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            mode = root / "mode"
            client = root / "psql"
            client.write_text('#!/bin/bash\ncase "$(cat "' + str(mode) + '\")" in\n'
                              'ok) echo \'[{"pool_name":"main","busy_pages":12,"dirty_pages":3,"all_pages":100},\n{"pool_name":"catalog","busy_pages":1}]\';;\n'
                              'fail) exit 2;;\n'
                              'hang) exec sleep 60;;\nesac\n')
            client.chmod(0o755)
            log = root / "stats.jsonl"
            mode.write_text("ok")
            proc = subprocess.Popen(["bash", str(SCRIPT), "0.05", "10", str(client), "5432", str(log)],
                                    start_new_session=True)
            def wait_for(predicate):
                deadline = time.monotonic() + 8
                while time.monotonic() < deadline:
                    rows = [json.loads(line) for line in log.read_text().splitlines()] if log.exists() else []
                    if predicate(rows):
                        return rows
                    time.sleep(0.02)
                self.fail("Collector did not emit expected samples")
            try:
                wait_for(lambda rows: rows and rows[-1]["available"])
                mode.write_text("fail")
                wait_for(lambda rows: rows and not rows[-1]["available"])
                mode.write_text("hang")
                count = len(log.read_text().splitlines())
                wait_for(lambda rows: len(rows) > count and not rows[-1]["available"])
                mode.write_text("ok")
                rows = wait_for(lambda rows: rows and rows[-1]["available"])
                self.assertGreaterEqual(rows[0]["elapsed"], 10)
                self.assertLess(rows[-1]["elapsed"] - rows[0]["elapsed"], 8)
                self.assertTrue(all("timestamp" in row for row in rows))
                self.assertEqual(12, rows[-1]["pools"][0]["busy_pages"])
                self.assertTrue(all("undo" not in row for row in rows))
                mode.write_text("hang")
                time.sleep(0.1)
            finally:
                os.killpg(proc.pid, signal.SIGTERM)
                proc.wait(timeout=5)
            before = log.read_bytes()
            time.sleep(0.1)
            self.assertEqual(before, log.read_bytes())

    def test_traced_collector_falls_back_to_pools(self):
        self.check_one_sample('''#!/bin/bash
[[ $* != *orioledb_undo_buffer_stats* && $* == *orioledb_page_stats* ]] || exit 1
echo '[{"pool_name":"main","busy_pages":12}]'
''', traced=True, undo=False)

    def test_traced_sample_contains_pools_and_exact_undo_positions(self):
        self.check_one_sample(r'''#!/bin/bash
[[ $* == *orioledb_page_stats* && $* == *orioledb_undo_buffer_stats* &&
   $* == *last_used_location::text* && $* == *written_location::text* ]] || exit 1
cat <<'JSON'
{"pools":[{"pool_name":"main","busy_pages":12}],
 "undo":[{"undo_type":"row","capacity_bytes":8,"last_used_location":"18446744073709551615","written_location":"18446744073709551612"}]}
JSON
''', traced=True, undo=True)

    def check_one_sample(self, script, *, traced, undo):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            client = root / "psql"
            client.write_text(script)
            client.chmod(0o755)
            log = root / "stats.jsonl"
            args = ["bash", str(SCRIPT), "60", "10", str(client), "5432", str(log)]
            if traced:
                args.append("undo")
            proc = subprocess.Popen(args, start_new_session=True)
            try:
                deadline = time.monotonic() + 5
                while not (log.exists() and log.stat().st_size) and time.monotonic() < deadline:
                    time.sleep(0.02)
                sample = json.loads(log.read_text())
                self.assertTrue(sample["available"])
                self.assertGreaterEqual(sample["elapsed"], 10)
                self.assertEqual(12, sample["pools"][0]["busy_pages"])
                self.assertEqual(undo, "undo" in sample)
                if undo:
                    self.assertEqual("18446744073709551615", sample["undo"][0]["last_used_location"])
                    self.assertEqual("18446744073709551612", sample["undo"][0]["written_location"])
            finally:
                os.killpg(proc.pid, signal.SIGTERM)
                proc.wait(timeout=5)
