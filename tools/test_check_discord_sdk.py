"""Synthetic archives only; no Discord SDK binaries are copied into tests."""
import io
import struct
import tempfile
import unittest
import zipfile
from pathlib import Path
from check_discord_sdk import elf_checks, inspect_aar


def elf(machine=183, align=16384, relro_end=16384):
    data = bytearray(176)
    data[:7] = b"\x7fELF\x02\x01\x01"
    struct.pack_into("<HHI", data, 16, 3, machine, 1)
    struct.pack_into("<Q", data, 32, 64)
    struct.pack_into("<HH", data, 54, 56, 2)
    struct.pack_into("<IIQQQQQQ", data, 64, 1, 5, 0, 0, 0, len(data), len(data), align)
    struct.pack_into("<IIQQQQQQ", data, 120, 0x6474E552, 4, 0, 0x3000, 0, 0, relro_end - 0x3000, 1)
    return bytes(data)


class SdkArchiveTest(unittest.TestCase):
    def archive(self, path, abis=("arm64-v8a", "x86_64"), align=16384, headers=True, bundled_jar=False):
        with zipfile.ZipFile(path, "w") as archive:
            for name in ("AndroidManifest.xml", "libs/discord_partner_sdk.jar" if bundled_jar else "classes.jar", "prefab/prefab.json", "prefab/modules/discord_partner_sdk/module.json"):
                archive.writestr(name, b"{}")
            if headers:
                archive.writestr("prefab/modules/discord_partner_sdk/include/discordpp.h", b"fixture")
            for abi in abis:
                prefix = f"prefab/modules/discord_partner_sdk/libs/android.{abi}/"
                archive.writestr(prefix + "abi.json", b"{}")
                archive.writestr(prefix + "libdiscord_partner_sdk.so", elf(183 if abi == "arm64-v8a" else 62, align))

    def check(self, **options):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "sdk.aar"
            self.archive(path, **options)
            return inspect_aar(path, "1.10.19337")

    def test_both_abis_and_aligned_segments_pass(self):
        report = self.check()
        self.assertTrue(report["passed"])
        self.assertEqual(2, len(report["libraries"]))
        self.assertEqual(64, len(report["sha256"]))

    def test_missing_emulator_abi_is_reported(self):
        self.assertFalse(self.check(abis=("arm64-v8a",))["passed"])

    def test_missing_header_is_reported(self):
        self.assertFalse(self.check(headers=False)["passed"])

    def test_official_bundled_jar_layout_is_accepted(self):
        self.assertTrue(self.check(bundled_jar=True)["passed"])

    def test_four_kilobyte_library_fails(self):
        self.assertFalse(self.check(align=4096)["passed"])

    def test_relro_end_must_be_aligned(self):
        data = elf(relro_end=0x3800)
        self.assertTrue(elf_checks(io.BytesIO(data), len(data), "arm64-v8a"))

    def test_diagnostic_values_do_not_change_failure_result(self):
        data = elf(relro_end=0x3800)
        segments = []
        errors = elf_checks(io.BytesIO(data), len(data), "arm64-v8a", segments)
        self.assertEqual(["RELRO 1: end is not aligned to 16 KB"], errors)
        self.assertEqual("0x3800", segments[1]["end"])
        self.assertEqual("0x3800", segments[1]["end_remainder_16kb"])
        self.assertEqual("0x4000", segments[0]["alignment"])

    def test_both_packaged_and_prefab_libraries_are_checked(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "sdk.aar"
            self.archive(path)
            with zipfile.ZipFile(path, "a") as archive:
                archive.writestr("jni/arm64-v8a/libdiscord_partner_sdk.so", elf(relro_end=0x3800))
            report = inspect_aar(path, "1.10.19337")
            self.assertFalse(report["passed"])
            packaged = next(row for row in report["libraries"] if row["entry"].startswith("jni/"))
            self.assertTrue(packaged["errors"])
            self.assertEqual("0x3800", packaged["segments"][1]["end"])

    def test_truncated_and_wrong_abi_do_not_pass(self):
        for data in (elf()[:32], elf()[:90], elf(machine=62)):
            self.assertTrue(elf_checks(io.BytesIO(data), len(data), "arm64-v8a"))

    def test_invalid_segment_bounds_do_not_pass(self):
        data = bytearray(elf())
        struct.pack_into("<Q", data, 64 + 32, 200)
        self.assertTrue(elf_checks(io.BytesIO(data), len(data), "arm64-v8a"))

    def test_alignment_congruence_is_checked(self):
        data = bytearray(elf())
        struct.pack_into("<Q", data, 64 + 16, 4096)
        self.assertTrue(elf_checks(io.BytesIO(data), len(data), "arm64-v8a"))

    def test_declared_version_gate_and_non_archive(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "sdk.aar"
            self.archive(path)
            for version in ("1.9.18337", "latest", "1.10"):
                self.assertFalse(inspect_aar(path, version)["passed"])
            path.write_bytes(b"not a zip")
            self.assertFalse(inspect_aar(path, "1.10.19337")["passed"])

    def test_targeting_arm64_does_not_require_x86(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "sdk.aar"
            self.archive(path, abis=("arm64-v8a",))
            self.assertTrue(inspect_aar(path, "1.10.19337", ("arm64-v8a",))["passed"])


if __name__ == "__main__":
    unittest.main()
