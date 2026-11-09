"""Check baseline safety and failure recovery without touching the shared lab."""
import importlib.util
import json
import os
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("snapshot", Path(__file__).parents[1] / "snapshot.py")
snapshot = importlib.util.module_from_spec(spec)
spec.loader.exec_module(snapshot)


class SnapshotTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.state = Path(self.temp.name) / "vagrant"
        self.state.mkdir()
        self.manifest = self.state.parent / "baseline.json"
        self.domains = {node: f"uuid-{node}" for node in snapshot.NODES}
        p = patch.dict(os.environ, VAGRANT_DOTFILE_PATH=str(self.state))
        p.start()
        self.addCleanup(p.stop)
        p = patch.object(snapshot, "machines", return_value=self.domains)
        p.start()
        self.addCleanup(p.stop)
        self.commands = []
        self.missing = None
        self.fail_capture = None
        self.has_snapshots = False
        p = patch.object(snapshot, "run", side_effect=self.command)
        p.start()
        self.addCleanup(p.stop)

    def command(self, *args):
        self.commands.append(args)
        if args[0] == "vagrant":
            return ""
        command, domain = args[3:5]
        if command == "snapshot-list":
            return snapshot.SNAPSHOT if self.has_snapshots else ""
        if command == "domstate":
            return "shut off"
        if command == "snapshot-dumpxml":
            if domain == self.missing:
                raise ValueError("missing snapshot")
            return "<domainsnapshot><state>shutoff</state></domainsnapshot>"
        if command == "snapshot-create-as" and domain == self.fail_capture:
            raise ValueError("disk full")
        return ""

    def save_manifest(self):
        self.manifest.write_text(json.dumps(self.domains))

    def mutations(self):
        return [c for c in self.commands if c[0] == "vagrant" or c[3] in
                ("snapshot-delete", "snapshot-create-as", "snapshot-revert")]

    def test_missing_baseline_does_not_touch_guests(self):
        with self.assertRaisesRegex(ValueError, "No complete baseline"):
            snapshot.manage("restore")
        self.assertEqual(self.mutations(), [])

    def test_recreated_vm_is_rejected_before_mutation(self):
        self.save_manifest()
        self.domains["n5"] = "new-uuid"
        with self.assertRaisesRegex(ValueError, "identities changed"):
            snapshot.manage("restore")
        self.assertEqual(self.mutations(), [])

    def test_missing_fifth_snapshot_does_not_partially_restore(self):
        self.save_manifest()
        self.missing = self.domains["n5"]
        with self.assertRaisesRegex(ValueError, "missing snapshot"):
            snapshot.manage("restore")
        self.assertEqual(self.mutations(), [])

    def test_reset_is_repeatable_and_never_provisions(self):
        self.save_manifest()
        for _ in range(2):
            snapshot.manage("restore")
        mutations = self.mutations()
        self.assertEqual(sum(c[0] == "virsh" for c in mutations), 10)
        self.assertEqual(mutations[0], ("vagrant", "halt", "--force"))
        self.assertEqual(mutations[-1], ("vagrant", "up", "--no-provision"))
        self.assertTrue(self.manifest.exists())

    def test_existing_snapshot_requires_explicit_replace(self):
        self.has_snapshots = True
        with self.assertRaisesRegex(ValueError, "already exists"):
            snapshot.manage("save")
        self.assertEqual(self.mutations(), [])

    def test_capture_publishes_only_a_complete_baseline(self):
        snapshot.manage("save")
        self.assertEqual(json.loads(self.manifest.read_text()), self.domains)
        self.assertEqual(self.mutations()[0], ("vagrant", "halt"))
        self.assertEqual(self.mutations()[-1], ("vagrant", "up", "--no-provision"))

    def test_failed_replacement_invalidates_manifest_and_does_not_start(self):
        self.save_manifest()
        self.has_snapshots = True
        self.fail_capture = self.domains["n3"]
        with self.assertRaisesRegex(ValueError, "disk full"):
            snapshot.manage("save", replace=True)
        self.assertFalse(self.manifest.exists())
        self.assertFalse(any(c[:2] == ("vagrant", "up") for c in self.commands))

    def test_non_qcow2_disk_is_rejected(self):
        for node in snapshot.NODES:
            path = self.state / "machines" / node / "libvirt"
            path.mkdir(parents=True)
            (path / "id").write_text("11111111-1111-1111-1111-111111111111")
        xml = ('<domain><uuid>11111111-1111-1111-1111-111111111111</uuid>'
               '<devices><disk device="disk"><driver type="raw"/></disk></devices></domain>')
        with patch.object(snapshot, "virsh", return_value=xml):
            # Access the real implementation hidden by setUp's mock.
            with self.assertRaisesRegex(ValueError, "qcow2"):
                self.real_machines(self.state)

    real_machines = staticmethod(snapshot.machines)
