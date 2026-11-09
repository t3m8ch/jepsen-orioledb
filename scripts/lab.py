#!/usr/bin/env python3
"""Run a lab command with per-user Vagrant state and an exclusive lock."""
import fcntl
import os
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent


def machine_ids(directory):
    return {
        str(path.relative_to(directory)): path.read_text().strip()
        for path in directory.glob("machines/*/libvirt/id")
        if path.read_text().strip()
    }


def adopt_legacy_state(destination):
    """Preserve existing VM identities and keys from registered worktrees."""
    if destination.exists() or (destination.parent / "vagrant-initialized").exists():
        destination.mkdir(mode=0o700, exist_ok=True)
        return
    output = subprocess.check_output(
        ["git", "worktree", "list", "--porcelain", "-z"], cwd=ROOT
    ).decode()
    candidates = []
    for field in output.split("\0"):
        if field.startswith("worktree "):
            directory = Path(field[9:]) / "infra/.vagrant"
            identities = machine_ids(directory)
            if identities:
                candidates.append((directory, identities))
    if candidates:
        source, identities = candidates[0]
        if any(other != identities for _, other in candidates[1:]):
            raise SystemExit(
                "Conflicting Vagrant states in registered worktrees; reconcile them "
                "before using the shared lab: "
                + ", ".join(str(path) for path, _ in candidates)
            )
        temporary = Path(tempfile.mkdtemp(prefix=".vagrant-", dir=destination.parent))
        try:
            shutil.copytree(source, temporary, dirs_exist_ok=True)
            temporary.rename(destination)
        finally:
            if temporary.exists():
                shutil.rmtree(temporary)
        print(f"Adopted existing Vagrant state from {source}", file=sys.stderr)
    else:
        destination.mkdir(mode=0o700)


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
        adopt_legacy_state(state / "vagrant")
        (state / "vagrant-initialized").touch(mode=0o600)
        os.environ["VAGRANT_DOTFILE_PATH"] = str(state / "vagrant")
        command = sys.argv[1:]
        os.chdir(ROOT / "infra" if command[0] == "vagrant" else ROOT)
        os.execvp(command[0], command)


if __name__ == "__main__":
    main()
