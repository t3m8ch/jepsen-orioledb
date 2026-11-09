"""Exercise state adoption and cross-checkout locking without booting VMs."""
import fcntl
import os
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).resolve().parents[1] / "lab.py"


class SharedLabTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="shared lab ")
        self.addCleanup(self.temporary.cleanup)
        self.base = Path(self.temporary.name)
        self.repo = self.base / "repo"
        self.repo.mkdir()
        subprocess.run(["git", "init", "-q", str(self.repo)], check=True)
        subprocess.run(
            ["git", "-c", "user.name=Test", "-c", "user.email=test@example.org",
             "commit", "-q", "--allow-empty", "-m", "Initial"], cwd=self.repo, check=True
        )
        self.other = self.base / "other checkout"
        subprocess.run(["git", "worktree", "add", "-q", "-b", "other", str(self.other)],
                       cwd=self.repo, check=True)
        for checkout in (self.repo, self.other):
            (checkout / "scripts").mkdir()
            (checkout / "infra").mkdir()
            shutil.copyfile(SCRIPT, checkout / "scripts/lab.py")
        self.env = dict(os.environ, XDG_STATE_HOME=str(self.base / "state"))
        self.state = self.base / "state/jepsen-orioledb"

    def run_lab(self, checkout, *args, **kwargs):
        return subprocess.run([sys.executable, str(checkout / "scripts/lab.py"), *args],
                              env=self.env, capture_output=True, text=True, **kwargs)

    def legacy(self, checkout, identity):
        directory = checkout / "infra/.vagrant/machines/n1/libvirt"
        directory.mkdir(parents=True)
        (directory / "id").write_text(identity)
        (directory / "private_key").write_text("test key")

    def test_adopts_existing_vms_and_keys_from_another_worktree(self):
        self.legacy(self.repo, "existing-vm")
        for checkout in (self.other, self.repo):
            result = self.run_lab(checkout, sys.executable, "-c",
                                  "import os; print(os.environ['VAGRANT_DOTFILE_PATH'])")
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(result.stdout.strip(), str(self.state / "vagrant"))
        adopted = self.state / "vagrant/machines/n1/libvirt"
        self.assertEqual((adopted / "id").read_text(), "existing-vm")
        self.assertEqual((adopted / "private_key").read_text(), "test key")
        self.assertEqual(self.state.stat().st_mode & 0o777, 0o700)
        # Destroying shared machines must not cause old identities to be re-imported.
        shutil.rmtree(self.state / "vagrant")
        self.assertEqual(self.run_lab(self.other, "true").returncode, 0)
        self.assertFalse((self.state / "vagrant/machines").exists())

    def test_refuses_conflicting_legacy_states(self):
        self.legacy(self.repo, "first-vm")
        self.legacy(self.other, "second-vm")
        result = self.run_lab(self.other, "true")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Conflicting Vagrant states", result.stderr)
        self.assertFalse((self.state / "vagrant").exists())

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
