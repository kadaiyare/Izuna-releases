"""Inspect a local Discord Android AAR without extracting it or executing SDK code.

This checks packaging and ELF segment alignment, not provenance, license eligibility,
SDK API compatibility, final APK ZIP alignment, or actual Rich Presence behavior.
"""
import argparse
import hashlib
import json
import re
import struct
import zipfile
from pathlib import Path


MACHINES = {"arm64-v8a": 183, "x86_64": 62}
PAGE_SIZE = 16384


def elf_checks(source, size, abi, segments=None):
    header = source.read(64)
    if len(header) != 64 or header[:7] != b"\x7fELF\x02\x01\x01":
        return ["Expected a complete little-endian ELF64 header"]
    kind, machine, version = struct.unpack_from("<HHI", header, 16)
    if kind != 3 or machine != MACHINES[abi] or version != 1:
        return ["ELF type, machine, or version does not match the ABI"]
    offset = struct.unpack_from("<Q", header, 32)[0]
    entry_size, count = struct.unpack_from("<HH", header, 54)
    if entry_size != 56 or not 1 <= count <= 512 or not 64 <= offset <= 8 * 1024 * 1024:
        return ["Invalid or unsupported program-header table"]
    if offset + entry_size * count > size:
        return ["Truncated program-header table"]
    if len(source.read(offset - 64)) != offset - 64:
        return ["Truncated ELF file"]
    table = source.read(entry_size * count)
    if len(table) != entry_size * count:
        return ["Truncated program-header table"]
    failures = []
    loads = 0
    for index in range(count):
        kind, _, file_offset, address, _, file_size, memory_size, align = struct.unpack_from("<IIQQQQQQ", table, index * entry_size)
        if segments is not None and kind in (1, 0x6474E552):
            segment = {"index": index, "type": "LOAD" if kind == 1 else "GNU_RELRO",
                       "offset": hex(file_offset), "virtual_address": hex(address),
                       "file_size": hex(file_size), "memory_size": hex(memory_size),
                       "alignment": hex(align)}
            if kind == 0x6474E552:
                segment.update(end=hex(address + memory_size),
                               end_remainder_16kb=hex((address + memory_size) % PAGE_SIZE))
            segments.append(segment)
        if kind == 1:  # PT_LOAD
            loads += 1
            if align < PAGE_SIZE or align & (align - 1):
                failures.append(f"LOAD {index}: alignment below 16 KB or not a power of two")
            elif file_offset % align != address % align:
                failures.append(f"LOAD {index}: file and virtual-address alignment differ")
            if file_size > memory_size or file_offset + file_size > size:
                failures.append(f"LOAD {index}: invalid segment bounds")
        elif kind == 0x6474E552 and (address + memory_size) % PAGE_SIZE:  # PT_GNU_RELRO
            failures.append(f"RELRO {index}: end is not aligned to 16 KB")
    if not loads:
        failures.append("No LOAD segments")
    return failures


def inspect_aar(path, sdk_version, required_abis=("arm64-v8a", "x86_64")):
    path = Path(path)
    report = {"file": path.name, "declared_sdk_version": sdk_version,
              "required_abis": list(required_abis), "libraries": [], "errors": []}
    match = re.fullmatch(r"(\d+)\.(\d+)\.(\d+)", sdk_version)
    if not match or tuple(map(int, match.groups())) < (1, 10, 0):
        report["errors"].append("Declare an SDK version >= 1.10.0 from the official download")
    if any(abi not in MACHINES for abi in required_abis) or not required_abis:
        report["errors"].append("Only arm64-v8a and x86_64 are supported by this checker")
        report["passed"] = False
        return report
    try:
        with path.open("rb") as source:
            digest = hashlib.sha256()
            for chunk in iter(lambda: source.read(1024 * 1024), b""):
                digest.update(chunk)
        report["sha256"] = digest.hexdigest()
        with zipfile.ZipFile(path) as archive:
            names = archive.namelist()
            required = ("AndroidManifest.xml", "prefab/prefab.json",
                        "prefab/modules/discord_partner_sdk/module.json")
            for name in required:
                if name not in names:
                    report["errors"].append(f"Missing {name}")
            if "classes.jar" not in names and "libs/discord_partner_sdk.jar" not in names:
                report["errors"].append("Missing SDK Java classes (classes.jar or libs/discord_partner_sdk.jar)")
            if not any(name.startswith("prefab/modules/discord_partner_sdk/include/") and name.endswith("/discordpp.h") for name in names):
                report["errors"].append("Missing Prefab discordpp.h")
            if len(names) != len(set(names)):
                report["errors"].append("Duplicate archive entries")
            for abi in required_abis:
                prefix = f"prefab/modules/discord_partner_sdk/libs/android.{abi}/"
                if prefix + "abi.json" not in names or prefix + "libdiscord_partner_sdk.so" not in names:
                    report["errors"].append(f"Missing Prefab library or ABI metadata for {abi}")
                libraries = [info for info in archive.infolist() if info.filename.endswith(".so") and
                             (info.filename.startswith(f"jni/{abi}/") or info.filename.startswith(prefix))]
                if not libraries:
                    report["errors"].append(f"No libraries for {abi}")
                for info in libraries:
                    segments = []
                    with archive.open(info) as source:
                        errors = elf_checks(source, info.file_size, abi, segments)
                    report["libraries"].append({"entry": info.filename, "errors": errors,
                                                "segments": segments})
    except (OSError, ValueError, zipfile.BadZipFile, RuntimeError, NotImplementedError) as error:
        # No archive contents or credentials are printed.
        report["errors"].append(f"Unable to inspect AAR ({type(error).__name__})")
    report["passed"] = not report["errors"] and all(not row["errors"] for row in report["libraries"])
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("aar", type=Path)
    parser.add_argument("--sdk-version", required=True, help="Version displayed by the official download; not independently verified")
    parser.add_argument("--abi", action="append", choices=tuple(MACHINES), help="Repeat to check selected ABIs; defaults to both")
    args = parser.parse_args()
    report = inspect_aar(args.aar, args.sdk_version, tuple(args.abi or MACHINES))
    print(json.dumps(report, ensure_ascii=True, indent=2))
    return 0 if report["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
