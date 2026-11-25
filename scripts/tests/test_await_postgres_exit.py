"""Exercise the VM's process wait script with a controllable ps."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

SCRIPT = Path(__file__).resolve().parents[2] / "jepsen/resources/await-postgres-exit.sh"


class AwaitPostgresExitTest(unittest.TestCase):
    def run_with_ps(self, body, budget="4s"):
        with tempfile.TemporaryDirectory() as tmp:
            ps = Path(tmp) / "ps"
            ps.write_text("#!/bin/bash\n" + body + "\n")
            ps.chmod(0o755)
            env = {**os.environ, "PATH": f"{tmp}{os.pathsep}{os.environ['PATH']}",
                   "PS_STATE": str(Path(tmp) / "state")}
            return subprocess.run(["timeout", budget, "bash", str(SCRIPT)],
                                  env=env, capture_output=True, text=True, timeout=6)

    def test_polls_until_only_zombies_and_other_commands_remain(self):
        result = self.run_with_ps('''
if [[ ! -e $PS_STATE ]]; then
    touch "$PS_STATE"
    echo '42 D io_wait postgres'
else
    printf '42 Z - postgres\\n43 S - other\\n'
fi
''')
        self.assertEqual(0, result.returncode)
        self.assertIn("42 D io_wait postgres", result.stderr)
        self.assertNotIn("42 Z", result.stderr)

    def test_no_matching_user_processes(self):
        self.assertEqual(0, self.run_with_ps("exit 1").returncode)

    def test_other_ps_failure_is_propagated(self):
        self.assertEqual(2, self.run_with_ps("exit 2").returncode)

    def test_individual_probe_timeout_is_distinct(self):
        result = self.run_with_ps("exec sleep 60")
        self.assertEqual(70, result.returncode)
        self.assertIn("ps timed out", result.stderr)

    def test_overall_timeout_retains_last_process_diagnostics(self):
        result = self.run_with_ps("echo '42 D io_wait postgres'", budget="2s")
        self.assertEqual(124, result.returncode)
        self.assertIn("42 D io_wait postgres", result.stderr)
        self.assertNotIn("ps timed out", result.stderr)
