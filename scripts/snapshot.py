#!/usr/bin/env python3
"""Manage offline libvirt baselines. Run only through scripts/lab.py."""
import argparse
import json
import os
import subprocess
import sys
import uuid
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
NODES = tuple(f"n{i}" for i in range(1, 6))
SNAPSHOT = "jepsen-baseline"


def run(*args):
    print("Running: " + " ".join(args), file=sys.stderr)
    result = subprocess.run(args, cwd=ROOT / "infra", check=True, text=True,
                            stdout=None if args[0] == "vagrant" else subprocess.PIPE,
                            env=dict(os.environ, LC_ALL="C"))
    return (result.stdout or "").strip()


def virsh(*args):
    return run("virsh", "--connect", "qemu:///system", *args)


def machines(state):
    result = {}
    for node in NODES:
        identity = state / "machines" / node / "libvirt" / "id"
        if not identity.exists():
            raise ValueError(f"{node} is not created; run mise run vm:up first")
        domain = str(uuid.UUID(identity.read_text().strip()))
        xml = ET.fromstring(virsh("dumpxml", domain))
        if xml.findtext("uuid") != domain:
            raise ValueError(f"Unexpected domain identity for {node}")
        disks = xml.findall("./devices/disk[@device='disk']")
        if not disks or any(d.find("driver") is None or d.find("driver").get("type") != "qcow2" for d in disks):
            raise ValueError(f"{node} requires qcow2 disks for internal snapshots")
        if any(d.get("snapshot", "internal") != "internal" for d in disks):
            raise ValueError(f"{node} requires internal snapshots of every disk")
        result[node] = domain
    return result


def validate_snapshot(domain):
    xml = ET.fromstring(virsh("snapshot-dumpxml", domain, SNAPSHOT))
    if xml.findtext("state") != "shutoff":
        raise ValueError(f"{domain}: baseline must have been saved while shut off")


def manage(action, replace=False):
    state = Path(os.environ["VAGRANT_DOTFILE_PATH"])
    manifest = state.parent / "baseline.json"
    domains = machines(state)
    if action == "save":
        existing = {node: SNAPSHOT in virsh("snapshot-list", domain, "--name").splitlines()
                    for node, domain in domains.items()}
        if (manifest.exists() or any(existing.values())) and not replace:
            raise ValueError("Baseline already exists; use vm:snapshot -- --replace to replace it")
        # Invalidate before mutating anything: a partial capture must never be restored.
        manifest.unlink(missing_ok=True)
        run("vagrant", "halt")
        for domain in domains.values():
            if virsh("domstate", domain) != "shut off":
                raise ValueError(f"{domain} did not shut off; no baseline saved")
        for node, domain in domains.items():
            if existing[node]:
                virsh("snapshot-delete", domain, SNAPSHOT)
            virsh("snapshot-create-as", domain, SNAPSHOT, "--atomic")
            validate_snapshot(domain)
        temporary = manifest.with_suffix(".tmp")
        temporary.write_text(json.dumps(domains, indent=2) + "\n")
        temporary.replace(manifest)
    else:
        if not manifest.exists():
            raise ValueError("No complete baseline; prepare clean VMs and run mise run vm:snapshot")
        if json.loads(manifest.read_text()) != domains:
            raise ValueError("VM identities changed; prepare a new baseline with vm:snapshot -- --replace")
        # Check all five before stopping or reverting any VM.
        for domain in domains.values():
            validate_snapshot(domain)
        run("vagrant", "halt", "--force")
        for domain in domains.values():
            virsh("snapshot-revert", domain, SNAPSHOT, "--force")
    # Keep the snapshot's disk contents, including Vagrant's SSH key, unchanged.
    run("vagrant", "up", "--no-provision")
    print(f"Baseline {action} completed for all five VMs.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("save", "restore"))
    parser.add_argument("--replace", action="store_true")
    args = parser.parse_args()
    if args.replace and args.action != "save":
        parser.error("--replace is only valid with save")
    try:
        manage(args.action, args.replace)
    except (ValueError, OSError, subprocess.CalledProcessError, ET.ParseError) as error:
        sys.exit(f"Baseline operation failed: {error}. VMs may remain stopped; fix the error and retry.")


if __name__ == "__main__":
    main()
