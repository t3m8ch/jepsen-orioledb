#!/usr/bin/env python3
import os
import shlex
import subprocess
import sys
from pathlib import Path


TRACE_FLAG = "--trace-evictions"


def build_args(args):
    """Select the export without consuming Jepsen's CLI options."""
    engine = "orioledb"
    trace = False
    options = iter(args)
    for arg in options:
        if arg == "--":
            break
        if arg == "--engine":
            engine = next(options, None)
        elif arg.startswith("--engine="):
            engine = arg.partition("=")[2]
        elif arg.partition("=")[0] == TRACE_FLAG:
            trace = True
    return [TRACE_FLAG] if trace and engine == "orioledb" else []


def main():
    root = Path(__file__).resolve().parent.parent

    # Refresh the export for the current inputs, reusing Podman's build cache.
    subprocess.run(["bash", "scripts/build.sh", *build_args(sys.argv[1:])], cwd=root, check=True)

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
    os.execvpe("lein", ["lein", "run", "test", *sys.argv[1:]], environment)


if __name__ == "__main__":
    main()
