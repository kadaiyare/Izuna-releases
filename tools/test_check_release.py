import json
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest.mock import patch

import check_release as audit


class ReleaseAuditTest(unittest.TestCase):
    def test_packaging_mistakes_block_release(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "app").mkdir()
            (root / ".github").mkdir()
            (root / "app/build.gradle.kts").write_text('versionName = "0.1.0"')
            (root / ".github/signing-cert-sha256.txt").write_text("abc123")
            apk = root / "test.apk"
            base = "  E: application\n    A: android:allowBackup(0x1)=false\n    A: android:usesCleartextTraffic(0x2)=false\n"
            for fault in (None, "backup", "cleartext", "debug", "min_sdk", "certificate", "secret", "key_file"):
                with self.subTest(fault=fault):
                    manifest = base.replace("allowBackup(0x1)=false", "allowBackup(0x1)=true") if fault == "backup" else base
                    if fault == "cleartext": manifest = manifest.replace("usesCleartextTraffic(0x2)=false", "usesCleartextTraffic(0x2)=true")
                    if fault == "debug": manifest += "    A: android:debuggable(0x3)=true\n"
                    badging = "versionName='0.1.0'\r\nminSdkVersion:'" + ("35" if fault == "min_sdk" else "36") + "'\r\n"
                    signature = "Signer #1 certificate SHA-256 digest: " + ("def456" if fault == "certificate" else "abc123")
                    with zipfile.ZipFile(apk, "w") as archive:
                        archive.writestr("assets/discord-sdk-notices.txt", "notices")
                        archive.writestr("assets/privacy.txt", "privacy")
                        archive.writestr("classes.dex", b"ghp_" + b"A" * 36 if fault == "secret" else b"ordinary bytecode")
                        if fault == "key_file": archive.writestr("private.jks", "private material")
                    with patch.object(audit, "ROOT", root), patch.object(audit, "command", side_effect=[badging.encode(), manifest.encode(), signature.encode()]):
                        result = audit.apk_check(apk, "aapt2", "apksigner")
                    self.assertEqual(bool(result["errors"]), fault is not None)

    def test_credentials_are_detected_without_echoing_values(self):
        value = b"ghp_" + b"A" * 36
        self.assertTrue(audit.sensitive(b"prefix " + value, []))
        self.assertTrue(audit.sensitive(b"private value", [b"private value"]))
        self.assertFalse(audit.sensitive(b"ordinary application metadata", []))
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "leak.txt").write_bytes(value)
            with patch.object(audit, "ROOT", root), patch.object(audit, "command", side_effect=[b"leak.txt\0", b""]), \
                    patch.object(audit.subprocess, "run") as process:
                process.return_value.stdout = b""
                result = audit.source_check()
            self.assertTrue(result["errors"])
            self.assertNotIn(value.decode(), json.dumps(result))

    def test_application_flags_cannot_be_overwritten_by_child(self):
        for namespace in ("android", "http://schemas.android.com/apk/res/android"):
            tree = f'''  E: manifest
      E: application
        A: {namespace}:allowBackup(0x1)=true
        A: {namespace}:usesCleartextTraffic(0x2)=false
          E: activity
            A: {namespace}:allowBackup(0x1)=false
      E: another
        A: {namespace}:usesCleartextTraffic(0x2)=true
'''
            flags = audit.manifest_attributes(tree, "application")
            self.assertEqual(flags, {"allowBackup": "true", "usesCleartextTraffic": "false"})

    def test_sdk_baseline_does_not_permit_new_errors_or_binary(self):
        baseline = json.loads((audit.ROOT / ".github/discord-sdk.json").read_text())
        result = {"sha256": baseline["sha256"], "passed": False, "errors": [], "libraries":
                  [{"entry": name, "errors": errors} for name, errors in baseline["known_library_errors"].items()]}
        with patch.object(audit, "inspect_aar", return_value=result):
            accepted = audit.sdk_check(Path("fixture.aar"))
            self.assertFalse(accepted["strict_16kb_passed"])
            self.assertEqual(accepted["errors"], [])
            result["libraries"][0]["errors"] = ["Unexpected executable segment"]
            self.assertTrue(audit.sdk_check(Path("fixture.aar"))["errors"])
            result["sha256"] = "unreviewed"
            self.assertIn("SDK checksum does not match the reviewed input", audit.sdk_check(Path("fixture.aar"))["errors"])

    def test_dependency_advisory_blocks_release(self):
        with tempfile.TemporaryDirectory() as directory:
            inventory = Path(directory) / "dependencies.txt"
            inventory.write_text("example:library:1.0\n")
            with patch.object(audit.urllib.request, "urlopen") as request:
                request.return_value.__enter__.return_value.read.return_value = b'{"results":[{"vulns":[{"id":"TEST-ADVISORY"}]}]}'
                result = audit.dependency_check(inventory)
            self.assertTrue(result["errors"])
            self.assertEqual(result["findings"][0]["advisories"], ["TEST-ADVISORY"])


if __name__ == "__main__":
    unittest.main()
