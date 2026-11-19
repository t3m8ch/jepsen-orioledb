#!/usr/bin/env python3
import argparse
import os
import shlex
import subprocess
import sys
from pathlib import Path


TRACE_FLAG = "--trace-evictions"


def needs_trace_build(args):
    """Return whether Jepsen needs an instrumented OrioleDB build."""
    parser = argparse.ArgumentParser(add_help=False, allow_abbrev=False, exit_on_error=False)
    parser.add_argument("--engine", default="orioledb")
    parser.add_argument(TRACE_FLAG, action="store_true")

    # tools.cli accepts an attached value on a boolean flag and ignores it.
    probe_args = [TRACE_FLAG if arg.startswith(TRACE_FLAG + "=") else arg for arg in args]
    try:
        options, _ = parser.parse_known_args(probe_args)
    except argparse.ArgumentError:
        # Leave CLI errors to Jepsen, which still receives the original arguments.
        return False
    return options.engine == "orioledb" and options.trace_evictions


def main():
    root = Path(__file__).resolve().parent.parent

    # Refresh the export for the current inputs, reusing Podman's build cache.
    args = sys.argv[1:]
    build = ["bash", "scripts/build.sh"]
    if needs_trace_build(args):
        build.append(TRACE_FLAG)
    subprocess.run(build, cwd=root, check=True)

    # Vagrant quotes paths containing spaces; shlex handles those quotes for us.
    result = subprocess.run(
        ["vagrant", "ssh-config", "n1"],
        cwd=root / "infra",
        stdout=subprocess.PIPE,
        text=True,
        check=True,
    )
    ssh = {}
    for line in result.stdout.splitlines():
        fields = shlex.split(line)
        if len(fields) == 2:
            ssh[fields[0]] = fields[1]

    environment = os.environ.copy()
    environment.update(
        LAB_ARTIFACT=str(root / ".cache/artifacts/current"),
        LAB_SSH_HOST=ssh["HostName"],
        LAB_SSH_PORT=ssh["Port"],
        LAB_SSH_USER=ssh["User"],
        LAB_SSH_KEY=ssh["IdentityFile"],
        LAB_DATA_HOST="192.168.77.11",
    )

    # Replace this process so Jepsen receives signals and returns its exit status.
    os.chdir(root / "jepsen")
    os.execvpe("lein", ["lein", "run", "test", *args], environment)


if __name__ == "__main__":
    main()
