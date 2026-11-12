"""Exercise shared state and cross-checkout locking without booting VMs."""
import fcntl
import json
import os
import select
import shlex
import shutil
import subprocess
import sys
import tempfile
import tomllib
import unittest
from pathlib import Path

SCRIPT = Path(__file__).resolve().parents[1] / "lab.py"


class SharedLabTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="shared lab ")
        self.addCleanup(self.temporary.cleanup)
        self.base = Path(self.temporary.name)
        self.repo = self.base / "repo"
        self.other = self.base / "other checkout"
        for checkout in (self.repo, self.other):
            (checkout / "scripts").mkdir(parents=True)
            (checkout / "infra").mkdir()
            shutil.copyfile(SCRIPT, checkout / "scripts/lab.py")
        self.env = dict(os.environ, XDG_STATE_HOME=str(self.base / "state"))
        self.state = self.base / "state/jepsen-orioledb"

    def run_lab(self, checkout, *args, **kwargs):
        return subprocess.run([sys.executable, str(checkout / "scripts/lab.py"), *args],
                              env=self.env, capture_output=True, text=True, **kwargs)

    def test_checkouts_share_persistent_state(self):
        result = self.run_lab(self.repo, sys.executable, "-c",
                              "import os; print(os.environ['VAGRANT_DOTFILE_PATH'])")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout.strip(), str(self.state / "vagrant"))
        (self.state / "vagrant/shared-data").write_text("existing state")
        result = self.run_lab(self.other, sys.executable, "-c",
                              "import os,pathlib; print((pathlib.Path(os.environ['VAGRANT_DOTFILE_PATH']) / 'shared-data').read_text())")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout.strip(), "existing state")
        self.assertEqual(self.state.stat().st_mode & 0o777, 0o700)

    def test_lock_survives_exec_and_serializes_checkouts(self):
        marker = self.base / "ready"
        holder = subprocess.Popen(
            [sys.executable, str(self.repo / "scripts/lab.py"), sys.executable, "-c",
             "import pathlib,sys; pathlib.Path(sys.argv[1]).touch(); print('ready', flush=True); sys.stdin.read()",
             str(marker)], env=self.env, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL, text=True,
        )
        self.addCleanup(lambda: holder.poll() is None and holder.kill())
        self.assertEqual(holder.stdout.readline().strip(), "ready")
        with (self.state / "lab.lock").open("a") as lock:
            with self.assertRaises(BlockingIOError):
                fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        contender = subprocess.Popen(
            [sys.executable, str(self.other / "scripts/lab.py"), "false"], env=self.env,
            stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True,
        )
        self.addCleanup(lambda: contender.poll() is None and contender.kill())
        self.assertIn("Waiting for lab lock", contender.stderr.readline())
        with self.assertRaises(subprocess.TimeoutExpired):
            contender.wait(timeout=0.2)
        holder.communicate(timeout=5)
        self.assertEqual(contender.wait(timeout=5), 1)
        contender.communicate()
        self.assertEqual(self.run_lab(self.repo, "true").returncode, 0)

    def test_default_path_and_relative_xdg_rejection(self):
        self.env.pop("XDG_STATE_HOME")
        self.env["HOME"] = str(self.base / "home")
        self.assertEqual(self.run_lab(self.repo, "true").returncode, 0)
        self.assertTrue((self.base / "home/.local/state/jepsen-orioledb/vagrant").is_dir())
        self.env["XDG_STATE_HOME"] = "relative"
        self.assertNotEqual(self.run_lab(self.repo, "true").returncode, 0)

    def test_vm_ssh_runs_during_locked_operation_without_holding_lock(self):
        shutil.copyfile(SCRIPT.with_name("ssh.py"), self.other / "scripts/ssh.py")
        binary = self.base / "bin"
        binary.mkdir()
        stub = binary / "vagrant"
        stub.write_text(
            f"#!{sys.executable}\n"
            "import json, os, sys\n"
            "if sys.argv[1] == 'status':\n"
            "    print('0,' + sys.argv[2] + ',state,running')\n"
            "else:\n"
            "    print(json.dumps([sys.argv[1:], os.getcwd(), "
            "os.environ['VAGRANT_DOTFILE_PATH']]), flush=True)\n"
            "    sys.stdin.read()\n"
        )
        stub.chmod(0o755)
        self.env["PATH"] = str(binary) + os.pathsep + self.env["PATH"]
        config = tomllib.loads((SCRIPT.parent.parent / "mise.toml").read_text())
        command = shlex.split(config["tasks"]["vm:ssh"]["run"]) + ["n3"]
        self.state.mkdir(parents=True)
        with (self.state / "lab.lock").open("a") as lock:
            fcntl.flock(lock, fcntl.LOCK_EX)
            shell = subprocess.Popen(command, cwd=self.other, env=self.env,
                                     stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                     stderr=subprocess.PIPE, text=True)
            try:
                self.assertTrue(select.select([shell.stdout], [], [], 3)[0],
                                "vm:ssh waits for the lab lock")
                line = shell.stdout.readline()
                self.assertTrue(line, "vm:ssh exited before starting the shell")
                self.assertEqual(json.loads(line), [
                    ["ssh", "n3"], str(self.other / "infra"), str(self.state / "vagrant")])
                self.assertIsNone(shell.poll())
                fcntl.flock(lock, fcntl.LOCK_UN)
                # A new operation can take the lock while the shell stays open.
                fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
                _, stderr = shell.communicate(timeout=3)
                self.assertEqual(shell.returncode, 0, stderr)
                self.assertNotIn("Waiting for lab lock", stderr)
            finally:
                if shell.poll() is None:
                    shell.kill()
                shell.communicate(timeout=3)

    def test_vagrant_runs_in_infra_and_preserves_arguments(self):
        binary = self.base / "bin"
        binary.mkdir()
        stub = binary / "vagrant"
        stub.write_text('#!/bin/sh\npwd\nprintf "%s\\n" "$@"\n')
        stub.chmod(0o755)
        self.env["PATH"] = str(binary) + os.pathsep + self.env["PATH"]
        result = self.run_lab(self.other, "vagrant", "ssh-config", "n1")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout.splitlines(), [str(self.other / "infra"), "ssh-config", "n1"])
