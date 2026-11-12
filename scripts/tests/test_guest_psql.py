"""Exercise the provisioned helper without requiring a VM."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
HELPER = (ROOT / 'infra/provision.sh').read_text().split("<<'LAB_PSQL'\n")[1].split('\nLAB_PSQL')[0]


class GuestPsqlTest(unittest.TestCase):
    def run_helper(self, client_body=None, args=()):
        with tempfile.TemporaryDirectory() as directory:
            client = Path(directory) / 'psql'
            if client_body is not None:
                client.write_text('#!/bin/bash\n' + client_body)
                client.chmod(0o755)
            helper = Path(directory) / 'lab-psql'
            helper.write_text(HELPER.replace('/opt/jepsen/pgsql/bin/psql', str(client)))
            return subprocess.run(['bash', str(helper), *args], capture_output=True, text=True,
                                  env={**os.environ, 'PGCONNECT_TIMEOUT': '99'})

    def test_missing_client(self):
        result = self.run_helper()
        self.assertEqual(1, result.returncode)
        self.assertIn('Start a Jepsen test', result.stderr)

    def test_connection_and_argument_forwarding(self):
        result = self.run_helper('printf "%s\\n" "$PGCONNECT_TIMEOUT" "$@"',
                                 ('-c', 'SELECT 1;'))
        self.assertEqual(0, result.returncode)
        self.assertEqual(['5', '-X', '--no-password', '--host=127.0.0.1', '--port=5432',
                          '--username=postgres', '--dbname=postgres', '-c', 'SELECT 1;'],
                         result.stdout.splitlines())

    def test_unavailable_database(self):
        result = self.run_helper('exit 2')
        self.assertEqual(2, result.returncode)
        self.assertIn('must be running on this VM', result.stderr)
        self.assertIn('/var/log/jepsen/postgresql.log', result.stderr)

    def test_sql_error_preserves_status(self):
        result = self.run_helper('exit 3')
        self.assertEqual(3, result.returncode)
        self.assertEqual('', result.stderr)
