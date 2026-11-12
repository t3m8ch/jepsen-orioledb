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
                              'ok) echo \'[{"pool_name":"main","busy_pages":12,"dirty_pages":3,"all_pages":100}]\';;\n'
                              'fail) exit 2;;\n'
                              'hang) sleep 60;;\nesac\n')
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
                mode.write_text("hang")
                time.sleep(0.1)
            finally:
                os.killpg(proc.pid, signal.SIGTERM)
                proc.wait(timeout=5)
            before = log.read_bytes()
            time.sleep(0.1)
            self.assertEqual(before, log.read_bytes())
