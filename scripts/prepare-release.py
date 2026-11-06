#!/usr/bin/env python3
"""Verify the signed APK and collect versioned assets for GitHub Releases."""

import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess


ROOT = Path(__file__).resolve().parent.parent
OUTPUT = ROOT / "app/build/outputs/apk/release"


def main():
    metadata = json.loads((OUTPUT / "output-metadata.json").read_text())
    if metadata["applicationId"] != "belowevolve.airremote" or metadata["variantName"] != "release":
        raise ValueError("Expected the production release APK")
    elements = metadata["elements"]
    if len(elements) != 1 or elements[0]["filters"]:
        raise ValueError("Obtainium release must contain one universal APK")
    element = elements[0]
    version = element["versionName"]
    if not re.fullmatch(r"\d+\.\d+\.\d+", version) or element["versionCode"] <= 0:
        raise ValueError("Expected a stable version with a positive versionCode")
    apk = (OUTPUT / element["outputFile"]).resolve()
    if apk.parent != OUTPUT.resolve():
        raise ValueError("APK must be inside the release output directory")

    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    properties = ROOT / "local.properties"
    if not sdk and properties.exists():
        for line in properties.read_text().splitlines():
            if line.startswith("sdk.dir="):
                sdk = line.split("=", 1)[1].replace("\\:", ":").replace("\\ ", " ").replace("\\\\", "\\")
                break
    if not sdk:
        raise ValueError("Set ANDROID_HOME to the Android SDK directory")
    tools = sorted(
        Path(sdk).glob("build-tools/*/apksigner"),
        key=lambda path: tuple(int(n) for n in re.findall(r"\d+", path.parent.name)),
    )
    if not tools:
        raise ValueError("Install Android SDK build-tools with apksigner")
    signature = subprocess.check_output([str(tools[-1]), "verify", "--verbose", "--print-certs", str(apk)], text=True)
    expected = (ROOT / "gradle/release-certificate.sha256").read_text().strip()
    signers = re.findall(r"^Signer #\d+ certificate SHA-256 digest: ([a-f0-9]{64})$", signature, re.MULTILINE)
    if signers != [expected]:
        raise ValueError("APK signing certificate changed. Restore the original .signing/ backup")
    print(signature, end="")
    # Compare actual APK identity with Gradle metadata before naming or publishing it.
    badging = subprocess.check_output([str(tools[-1].with_name("aapt2")), "dump", "badging", str(apk)], text=True)
    package = re.search(r"^package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'", badging)
    if not package or package.groups() != (metadata["applicationId"], str(element["versionCode"]), version):
        raise ValueError("APK identity does not match Gradle release metadata")

    destination = ROOT / "artifacts/release" / version
    destination.mkdir(parents=True, exist_ok=True)
    target = destination / f"Air-Remote-{version}.apk"
    shutil.copyfile(apk, target)
    digest = hashlib.sha256(target.read_bytes()).hexdigest()
    (destination / "SHA256SUMS.txt").write_text(f"{digest}  {target.name}\n")
    print(f"Release assets: {destination}")


if __name__ == "__main__":
    main()
