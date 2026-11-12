#!/usr/bin/env python3
"""Open a Vagrant SSH shell; invoke through lab.py --no-lock for shared state."""
import argparse
import os
import subprocess
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("vm", choices=[f"n{i}" for i in range(1, 6)],
                        help="lab VM to connect to (n1–n5)")
    args = parser.parse_args()
    os.chdir(Path(__file__).resolve().parent.parent / "infra")
    result = subprocess.run(["vagrant", "status", args.vm, "--machine-readable"],
                            capture_output=True, text=True)
    if result.returncode:
        raise SystemExit(f"Cannot query {args.vm}. Run mise run vm:status to diagnose.\n"
                         + result.stderr.strip())
    states = [line.split(",")[3] for line in result.stdout.splitlines()
              if len(line.split(",")) >= 4
              and line.split(",")[1:3] == [args.vm, "state"]]
    if states != ["running"]:
        state = states[-1] if states else "unknown"
        raise SystemExit(f"VM {args.vm} is unavailable (state: {state}). "
                         "Run mise run vm:up, then retry.")
    # Inherit the terminal, signals, and exit status for the full shell.
    os.execvp("vagrant", ["vagrant", "ssh", args.vm])


if __name__ == "__main__":
    main()
