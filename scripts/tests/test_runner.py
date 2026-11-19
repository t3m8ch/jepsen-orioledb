"""Check import safety and Jepsen handoff without building or booting VMs."""
import importlib.util
import subprocess
import unittest
from pathlib import Path
from unittest.mock import patch

SCRIPT = Path(__file__).resolve().parents[1] / "test.py"


def load_runner():
    spec = importlib.util.spec_from_file_location("lab_test_runner", SCRIPT)
    runner = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(runner)
    return runner


class TestRunnerTests(unittest.TestCase):
    def test_import_has_no_side_effects(self):
        with patch("subprocess.run") as run, patch("os.chdir") as chdir, \
                patch("os.execvpe") as execute:
            runner = load_runner()
        self.assertTrue(callable(runner.main))
        run.assert_not_called()
        chdir.assert_not_called()
        execute.assert_not_called()

    def test_builds_once_and_forwards_ssh_settings_and_arguments(self):
        runner = load_runner()
        root = SCRIPT.parent.parent
        config = '''Host n1
  HostName 192.168.121.10
  Port 2222
  User vagrant
  IdentityFile "/tmp/key with spaces"
'''
        with patch.object(runner.subprocess, "run", side_effect=[
                subprocess.CompletedProcess([], 0),
                subprocess.CompletedProcess([], 0, config)]) as run, \
                patch.object(runner.os, "chdir") as chdir, \
                patch.object(runner.os, "execvpe") as execute, \
                patch("sys.argv", ["test.py", "--time-limit", "5"]):
            runner.main()
        self.assertEqual(run.call_count, 2)
        self.assertEqual(run.call_args_list[0].args[0], ["bash", "scripts/build.sh"])
        self.assertEqual(run.call_args_list[1].args[0], ["vagrant", "ssh-config", "n1"])
        chdir.assert_called_once_with(root / "jepsen")
        command, args, environment = execute.call_args.args
        self.assertEqual(command, "lein")
        self.assertEqual(args, ["lein", "run", "test", "--time-limit", "5"])
        self.assertEqual(environment["LAB_SSH_HOST"], "192.168.121.10")
        self.assertEqual(environment["LAB_SSH_PORT"], "2222")
        self.assertEqual(environment["LAB_SSH_USER"], "vagrant")
        self.assertEqual(environment["LAB_SSH_KEY"], "/tmp/key with spaces")
        self.assertEqual(environment["LAB_ARTIFACT"], str(root / ".cache/artifacts/current"))
        self.assertEqual(environment["LAB_DATA_HOST"], "192.168.77.11")

    def test_trace_variant_is_selected_without_consuming_jepsen_options(self):
        runner = load_runner()
        flag = "--trace-evictions"
        for args, trace in [([flag], True),
                            ([flag + "=true"], True),
                            (["--time-limit", "5", flag], True),
                            ([flag, "--engine", "orioledb"], True),
                            (["--engine=orioledb", flag], True),
                            ([flag, "--engine", "postgres"], False),
                            (["--engine=postgres", flag], False),
                            (["--", flag], False),
                            (["--", flag + "=true"], False),
                            ([flag, "--engine"], False)]:
            with self.subTest(args=args), \
                    patch.object(runner.subprocess, "run", side_effect=[
                        subprocess.CompletedProcess([], 0),
                        subprocess.CompletedProcess([], 0, "HostName host\nPort 22\nUser vagrant\nIdentityFile key\n")]) as run, \
                    patch.object(runner.os, "chdir"), \
                    patch.object(runner.os, "execvpe") as execute, \
                    patch("sys.argv", ["test.py", *args]):
                runner.main()
                build = ["bash", "scripts/build.sh"] + ([flag] if trace else [])
                self.assertEqual(run.call_args_list[0].args[0], build)
                self.assertEqual(execute.call_args.args[1], ["lein", "run", "test", *args])

    def test_failed_build_does_not_start_jepsen(self):
        runner = load_runner()
        with patch.object(runner.subprocess, "run", side_effect=
                          subprocess.CalledProcessError(1, "build")) as run, \
                patch.object(runner.os, "execvpe") as execute:
            with self.assertRaises(subprocess.CalledProcessError):
                runner.main()
        self.assertEqual(run.call_count, 1)
        execute.assert_not_called()
