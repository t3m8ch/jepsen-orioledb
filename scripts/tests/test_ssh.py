"""Exercise VM selection and Vagrant handoff without opening a real shell."""
import importlib.util
import subprocess
import unittest
from pathlib import Path
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("lab_ssh", Path(__file__).parents[1] / "ssh.py")
ssh = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ssh)


class SshTests(unittest.TestCase):
    def invoke(self, args, output="", returncode=0):
        with patch("sys.argv", ["ssh.py", *args]), \
                patch.object(ssh.os, "chdir") as chdir, \
                patch.object(ssh.subprocess, "run", return_value=subprocess.CompletedProcess(
                    [], returncode, output, "provider failed")) as run, \
                patch.object(ssh.os, "execvp") as execute:
            self.run = run
            self.execute = execute
            ssh.main()
            return chdir, run, execute

    def test_all_five_nodes_use_vagrant_and_inherit_terminal(self):
        for node in (f"n{i}" for i in range(1, 6)):
            with self.subTest(node=node):
                chdir, run, execute = self.invoke([node], f"0,{node},state,running\n")
                chdir.assert_called_once_with(Path(ssh.__file__).resolve().parent.parent / "infra")
                run.assert_called_once_with(["vagrant", "status", node, "--machine-readable"],
                                            capture_output=True, text=True)
                execute.assert_called_once_with("vagrant", ["vagrant", "ssh", node])

    def test_missing_invalid_and_extra_arguments_do_not_run_vagrant(self):
        for args in ([], ["n6"], ["--helpful"], ["n1", "n2"]):
            with self.subTest(args=args):
                with self.assertRaises(SystemExit) as error:
                    self.invoke(args)
                self.assertEqual(error.exception.code, 2)
                self.run.assert_not_called()

    def test_unavailable_states_give_recovery_command(self):
        for output in ("", "0,n1,state,not_created\n", "0,n1,state,shutoff\n",
                       "0,n2,state,running\n"):
            with self.subTest(output=output):
                with self.assertRaisesRegex(SystemExit, "mise run vm:up"):
                    self.invoke(["n1"], output)
                self.execute.assert_not_called()

    def test_provider_failure_is_actionable(self):
        with self.assertRaisesRegex(SystemExit, "mise run vm:status.*\nprovider failed"):
            self.invoke(["n1"], returncode=1)
