#!/usr/bin/env python3
"""Run a lab command with per-user Vagrant state; lock unless --no-lock is given."""
import fcntl
import os
import sys
from contextlib import nullcontext
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent


def main():
    command = sys.argv[1:]
    no_lock = bool(command and command[0] == "--no-lock")
    if no_lock:
        command = command[1:]
    if not command:
        raise SystemExit("Usage: python3 scripts/lab.py [--no-lock] COMMAND [ARG ...]")
    state_home = Path(os.environ.get("XDG_STATE_HOME") or Path.home() / ".local/state")
    if not state_home.is_absolute():
        raise SystemExit("XDG_STATE_HOME must be an absolute path")
    state = state_home / "jepsen-orioledb"
    state.mkdir(parents=True, exist_ok=True, mode=0o700)
    with (nullcontext() if no_lock else (state / "lab.lock").open("a")) as lock:
        if lock is not None:
            print(f"Waiting for lab lock: {lock.name}", file=sys.stderr)
            fcntl.flock(lock, fcntl.LOCK_EX)
            # Keep the descriptor through exec and the entire Jepsen process lifetime.
            os.set_inheritable(lock.fileno(), True)
        (state / "vagrant").mkdir(mode=0o700, exist_ok=True)
        os.environ["VAGRANT_DOTFILE_PATH"] = str(state / "vagrant")
        os.chdir(ROOT / "infra" if command[0] == "vagrant" else ROOT)
        os.execvp(command[0], command)


if __name__ == "__main__":
    main()
