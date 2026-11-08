#!/usr/bin/env python3
"""Build the Windows companion and its NSIS installer on Windows, macOS or Linux."""
import argparse
import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import sys
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[1]
DRIVER_URL = "https://download.vb-audio.com/Download_CABLE/VBCABLE_Driver_Pack45.zip"
DRIVER_SHA256 = "b950e39f01af1d04ea623c8f6d8eb9b6ea5c477c637295fabf20631c85116bfb"


def run(*command):
    subprocess.run(command, cwd=ROOT, check=True)


def prepare_driver():
    bundle = ROOT / "target/bundle"
    bundle.mkdir(parents=True, exist_ok=True)
    archive = bundle / "VBCABLE_Driver_Pack45.zip"
    if not archive.exists():
        temporary = archive.with_suffix(".part")
        try:
            with urllib.request.urlopen(DRIVER_URL, timeout=60) as response, temporary.open("wb") as output:
                shutil.copyfileobj(response, output)
            if hashlib.sha256(temporary.read_bytes()).hexdigest() != DRIVER_SHA256:
                raise ValueError("VB-CABLE archive checksum mismatch")
            temporary.replace(archive)
        finally:
            temporary.unlink(missing_ok=True)
    if hashlib.sha256(archive.read_bytes()).hexdigest() != DRIVER_SHA256:
        raise ValueError(f"VB-CABLE checksum mismatch: remove {archive} and retry")
    with zipfile.ZipFile(archive) as files:
        files.extractall(bundle / "vbcable")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--target",
        choices=("x86_64-pc-windows-gnu", "x86_64-pc-windows-msvc"),
        default="x86_64-pc-windows-msvc" if os.name == "nt" else "x86_64-pc-windows-gnu",
    )
    target = parser.parse_args().target
    nsis = shutil.which("makensis")
    if not nsis and os.name == "nt":
        candidate = Path(os.environ.get("ProgramFiles(x86)", r"C:\Program Files (x86)")) / "NSIS/makensis.exe"
        if candidate.is_file():
            nsis = str(candidate)
    if not nsis:
        raise SystemExit("Install NSIS (makensis) before building the installer")
    prepare_driver()
    run("cargo", "build", "--locked", "--release", "--target", target)
    run(sys.executable, "scripts/collect-notices.py", "--target", target)
    package = subprocess.check_output(["cargo", "pkgid", "--locked"], cwd=ROOT, text=True).strip()
    version = package.rsplit("@", 1)[1]
    (ROOT.parent / "artifacts").mkdir(exist_ok=True)
    option = "/" if os.name == "nt" else "-"
    run(
        nsis, f"{option}V2", f"{option}DVERSION={version}",
        f"{option}DCOMPANION_BINARY=..\\target\\{target}\\release\\air-remote-companion.exe",
        "installer/setup.nsi",
    )
    print(ROOT.parent / "artifacts" / f"Air-Companion-Setup-{version}.exe")


if __name__ == "__main__":
    main()
