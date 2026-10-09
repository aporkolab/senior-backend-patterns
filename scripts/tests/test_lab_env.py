"""Check that lab credentials are private, stable and never silently replaced."""

import importlib.util
import contextlib
import io
import os
from pathlib import Path
import secrets
import stat
import tempfile
import unittest
from unittest.mock import patch


SCRIPT = Path(__file__).resolve().parents[2] / ".devcontainer" / "init-lab-env.py"
SPEC = importlib.util.spec_from_file_location("init_lab_env", SCRIPT)
ENV = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(ENV)


class LabEnvironmentTests(unittest.TestCase):
    def test_private_random_credential_is_retained_on_rebuild(self):
        with tempfile.TemporaryDirectory() as directory:
            first = Path(directory) / "first.env"
            second = Path(directory) / "second.env"
            password = ENV.ensure_password(first)
            self.assertRegex(password, r"^[0-9a-f]{64}$")
            self.assertEqual(stat.S_IMODE(first.stat().st_mode), 0o600)
            self.assertEqual(first.read_text(), f"LAB_DB_PASSWORD={password}\n")
            self.assertEqual(ENV.ensure_password(first), password)
            self.assertNotEqual(ENV.ensure_password(second), password)

    def test_invalid_existing_file_is_not_overwritten(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / ".env"
            path.write_text("invalid existing file\n")
            with self.assertRaisesRegex(ValueError, "invalid"):
                ENV.ensure_password(path)
            self.assertEqual(path.read_text(), "invalid existing file\n")

    def test_existing_symlink_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory) / "target"
            target.write_text("must not change\n")
            path = Path(directory) / ".env"
            path.symlink_to(target)
            with self.assertRaisesRegex(ValueError, "symlink"):
                ENV.ensure_password(path)
            self.assertEqual(target.read_text(), "must not change\n")

    def test_local_initialization_does_not_print_the_password(self):
        password = secrets.token_hex(32)
        output = io.StringIO()
        with patch.object(ENV, "ensure_password", return_value=password), \
                patch.dict(os.environ, {"GITHUB_ACTIONS": "false"}), \
                contextlib.redirect_stdout(output):
            self.assertEqual(ENV.main(), 0)
        self.assertNotIn(password, output.getvalue())

    def test_actions_registers_mask_before_status_output(self):
        password = secrets.token_hex(32)
        output = io.StringIO()
        with patch.object(ENV, "ensure_password", return_value=password), \
                patch.dict(os.environ, {"GITHUB_ACTIONS": "true"}), \
                contextlib.redirect_stdout(output):
            self.assertEqual(ENV.main(), 0)
        self.assertEqual(output.getvalue().splitlines()[0], f"::add-mask::{password}")


if __name__ == "__main__":
    unittest.main()
