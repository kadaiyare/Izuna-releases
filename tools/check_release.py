"""Fail closed on release packaging mistakes; never print credential values."""
import argparse
import hashlib
import io
import json
import re
import struct
import subprocess
import sys
import urllib.request
import zipfile
from pathlib import Path

from check_discord_sdk import inspect_aar

ROOT = Path(__file__).resolve().parents[1]
TOKEN_PATTERNS = [rb"gh[pousr]_[A-Za-z0-9]{30,}", rb"github_pat_[A-Za-z0-9_]{40,}",
                  rb"AKIA[0-9A-Z]{16}", rb"-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----"]


def command(*args):
    return subprocess.check_output(args, cwd=ROOT, stderr=subprocess.DEVNULL)


def developer_credentials():
    file = ROOT / "local.properties"
    if not file.is_file():
        return []
    # Match only the developer Last.fm credentials, never SDK paths or public application IDs.
    result = []
    for line in file.read_text(encoding="utf-8").splitlines():
        if re.match(r"\s*lastfm\.(?:apiKey|sharedSecret)\s*=", line):
            value = line.split("=", 1)[1].strip().encode()
            if len(value) >= 16:
                result.append(value)
    return result


def sensitive(data, credentials):
    return any(re.search(pattern, data) for pattern in TOKEN_PATTERNS) or any(value in data for value in credentials)


def source_check():
    credentials = developer_credentials()
    files = command("git", "ls-files", "-z").decode().split("\0")
    errors = []
    for name in filter(None, files):
        path = Path(name)
        if name == "local.properties" or name.startswith((".release/", "vendor/discord/")) or path.suffix in (".jks", ".keystore", ".p12"):
            errors.append(f"Private build input is tracked: {name}")
        if (ROOT / path).is_file() and sensitive((ROOT / path).read_bytes(), credentials):
            errors.append(f"Credential pattern found in tracked file: {name}")
    # Scan historical blobs without displaying their contents or author addresses.
    blobs = command("git", "rev-list", "--objects", "--all").decode().splitlines()
    payload = "\n".join(line.split(" ", 1)[0] for line in blobs) + "\n"
    result = subprocess.run(["git", "cat-file", "--batch"], input=payload.encode(), stdout=subprocess.PIPE,
                            stderr=subprocess.DEVNULL, check=True, cwd=ROOT)
    stream = io.BytesIO(result.stdout)
    historical_findings = 0
    while header := stream.readline():
        fields = header.decode().split()
        if len(fields) != 3:
            continue
        data = stream.read(int(fields[2])); stream.read(1)
        if fields[1] == "blob" and sensitive(data, credentials):
            historical_findings += 1
    if historical_findings:
        errors.append(f"Credential patterns in historical blobs: {historical_findings} (values withheld)")
    return {"checked_files": len(files) - 1, "history_objects": len(blobs), "errors": errors}


def dependency_check(file):
    coordinates = sorted(set(filter(None, file.read_text().splitlines())))
    queries = []
    for coordinate in coordinates:
        group, artifact, version = coordinate.split(":")
        queries.append({"package": {"ecosystem": "Maven", "name": f"{group}:{artifact}"}, "version": version})
    request = urllib.request.Request("https://api.osv.dev/v1/querybatch", data=json.dumps({"queries": queries}).encode(),
                                     headers={"Content-Type": "application/json"}, method="POST")
    with urllib.request.urlopen(request, timeout=60) as response:
        data = json.loads(response.read(8 * 1024 * 1024))
    if len(data.get("results", [])) != len(coordinates):
        raise ValueError("Incomplete vulnerability response")
    findings = [{"package": coordinate, "advisories": [row["id"] for row in result.get("vulns", [])]}
                for coordinate, result in zip(coordinates, data["results"]) if result.get("vulns")]
    return {"source": "https://api.osv.dev", "packages": len(coordinates), "findings": findings,
            "errors": ["Known dependency advisories require review"] if findings else [],
            "scope": "Resolved Maven runtime dependencies only; proprietary SDK internals are not indexed here"}


def sdk_check(aar):
    pin = json.loads((ROOT / ".github/discord-sdk.json").read_text())
    result = inspect_aar(aar, pin["version"])
    errors = list(result["errors"])
    if result.get("sha256") != pin["sha256"]:
        errors.append("SDK checksum does not match the reviewed input")
    # Keep the strict inspector unchanged. An exact known compatibility defect is recorded separately.
    actual = {row["entry"]: row["errors"] for row in result["libraries"] if row["errors"]}
    if actual != pin["known_library_errors"]:
        errors.append("SDK static failures differ from the explicitly documented compatibility baseline")
    return {"sha256": result.get("sha256"), "strict_16kb_passed": result["passed"], "errors": errors,
            "known_compatibility_defects": actual}


def manifest_attributes(tree, element):
    match = re.search(r"(?m)^(\s*)E: " + re.escape(element) + r"(?:\s|$)", tree)
    if not match:
        return {}
    block = tree[match.end():]
    result = {}
    for line in block.splitlines():
        # A child element's attributes must never override the application's flags.
        if re.match(r"\s*E:", line):
            break
        attribute = re.search(r"A: (?:android|http://schemas.android.com/apk/res/android):(\w+)\([^)]*\)=([^\r\n]+)", line)
        if attribute:
            result[attribute.group(1)] = attribute.group(2)
    return result


def apk_check(apk, aapt2, apksigner):
    errors = []
    badging = command(aapt2, "dump", "badging", str(apk)).decode()
    manifest = command(aapt2, "dump", "xmltree", str(apk), "--file", "AndroidManifest.xml").decode()
    app = manifest_attributes(manifest, "application")
    if "application-debuggable" in badging or app.get("debuggable", "").strip() not in ("", "false", "0x0", "0x00000000"):
        errors.append("APK is debuggable")
    for attribute in ("allowBackup", "usesCleartextTraffic"):
        if app.get(attribute, "").strip() not in ("false", "0x0", "0x00000000"):
            errors.append(f"Manifest must explicitly disable {attribute}")
    if not any(line in ("minSdkVersion:'36'", "sdkVersion:'36'") for line in badging.splitlines()):
        errors.append("Minimum Android version changed")
    version = re.search(r'versionName = "([0-9]+\.[0-9]+\.[0-9]+)"', (ROOT / "app/build.gradle.kts").read_text())
    if not version or f"versionName='{version[1]}'" not in badging:
        errors.append("APK version must match the release source")
    for permission in ("RECORD_AUDIO", "FOREGROUND_SERVICE_MICROPHONE", "MANAGE_EXTERNAL_STORAGE", "QUERY_ALL_PACKAGES", "REQUEST_INSTALL_PACKAGES"):
        if f"android.permission.{permission}" in manifest:
            errors.append(f"Unnecessary permission: {permission}")
    if "com.discord.socialsdk.AuthenticationActivity" in manifest or "com.discord.socialsdk.ForegroundService" in manifest:
        errors.append("Unused Discord component is packaged")
    credentials = developer_credentials()
    native = []
    with zipfile.ZipFile(apk) as archive:
        for entry in archive.infolist():
            if entry.filename.startswith((".release/", "vendor/")) or entry.filename.endswith((".jks", ".keystore", "local.properties")):
                errors.append(f"Private file is packaged: {entry.filename}")
            if entry.filename.endswith(".dex") and sensitive(archive.read(entry), credentials):
                errors.append("Credential pattern found in APK bytecode (values withheld)")
            if entry.filename.endswith(".so"):
                data = archive.read(entry)
                if data[:7] != b"\x7fELF\x02\x01\x01":
                    errors.append(f"Unsupported native binary: {entry.filename}"); continue
                offset = struct.unpack_from("<Q", data, 32)[0]
                size, count = struct.unpack_from("<HH", data, 54)
                has_relro = False
                for index in range(count):
                    kind, flags, *_ = struct.unpack_from("<IIQQQQQQ", data, offset + index * size)
                    has_relro |= kind == 0x6474E552
                    if (kind == 1 and flags & 3 == 3) or (kind == 0x6474E551 and flags & 1):
                        errors.append(f"Writable executable memory or executable stack: {entry.filename}")
                if not has_relro:
                    errors.append(f"No RELRO segment: {entry.filename}")
                native.append(entry.filename)
        if "assets/discord-sdk-notices.txt" not in archive.namelist():
            errors.append("SDK third-party notices are missing")
        if "assets/privacy.txt" not in archive.namelist():
            errors.append("In-app privacy information is missing")
    signature = command(apksigner, "verify", "--verbose", "--print-certs", str(apk)).decode()
    fingerprint = re.search(r"Signer #1 certificate SHA-256 digest: ([0-9a-fA-F]+)", signature)
    expected = (ROOT / ".github/signing-cert-sha256.txt").read_text().strip().lower()
    if not fingerprint or fingerprint[1].lower() != expected:
        errors.append("APK signing certificate differs from the release certificate")
    return {"sha256": hashlib.sha256(apk.read_bytes()).hexdigest(), "native_libraries": native,
            "certificate_sha256": fingerprint[1] if fingerprint else None, "errors": errors}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", action="store_true")
    parser.add_argument("--dependencies", type=Path)
    parser.add_argument("--sdk", type=Path)
    parser.add_argument("--apk", type=Path)
    parser.add_argument("--aapt2")
    parser.add_argument("--apksigner")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    report = {}
    try:
        if args.source: report["source"] = source_check()
        if args.dependencies: report["dependencies"] = dependency_check(args.dependencies)
        if args.sdk: report["sdk"] = sdk_check(args.sdk)
        if args.apk: report["apk"] = apk_check(args.apk, args.aapt2, args.apksigner)
    except Exception as failure:
        report["audit"] = {"errors": [f"Audit could not complete ({type(failure).__name__}); do not release"]}
    report["passed"] = bool(report) and all(not value.get("errors") for value in report.values())
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, indent=2))
    return 0 if report["passed"] else 1


if __name__ == "__main__":
    sys.exit(main())
