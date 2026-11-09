#!/usr/bin/env python3
"""Run a lab command with per-user Vagrant state and an exclusive lock."""
import fcntl
import os
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent


def main():
    if len(sys.argv) < 2:
        raise SystemExit("Usage: python3 scripts/lab.py COMMAND [ARG ...]")
    state_home = Path(os.environ.get("XDG_STATE_HOME") or Path.home() / ".local/state")
    if not state_home.is_absolute():
        raise SystemExit("XDG_STATE_HOME must be an absolute path")
    state = state_home / "jepsen-orioledb"
    state.mkdir(parents=True, exist_ok=True, mode=0o700)
    # Keep the descriptor through exec and the entire Jepsen process lifetime.
    with (state / "lab.lock").open("a") as lock:
        print(f"Waiting for lab lock: {lock.name}", file=sys.stderr)
        fcntl.flock(lock, fcntl.LOCK_EX)
        os.set_inheritable(lock.fileno(), True)
        (state / "vagrant").mkdir(mode=0o700, exist_ok=True)
        os.environ["VAGRANT_DOTFILE_PATH"] = str(state / "vagrant")
        command = sys.argv[1:]
        os.chdir(ROOT / "infra" if command[0] == "vagrant" else ROOT)
        os.execvp(command[0], command)


if __name__ == "__main__":
    main()
