#!/usr/bin/env python3
"""Add cache-only clean artwork to an already patched film.mov APK; never install it."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import zipfile


ROOT = Path(__file__).resolve().parent
OPEN = "openFile(Landroid/net/Uri;Ljava/lang/String;)Landroid/os/ParcelFileDescriptor;"


def run(command, env):
    result = subprocess.run([str(arg) for arg in command], env=env, capture_output=True, text=True)
    if result.returncode:
        raise RuntimeError(f"{Path(command[0]).name} failed:\n{result.stdout[-4000:]}{result.stderr[-4000:]}")
    return result.stdout


def patch_provider(text):
    if "Lmov/film/CleanArtwork;" in text:
        raise ValueError("Clean artwork patch already present")
    matches = list(re.finditer(r"^\.method [^\n]*" + re.escape(OPEN) + r"\n.*?^\.end method", text, re.M | re.S))
    if len(matches) != 1:
        raise ValueError("TvArt.openFile signature changed")
    match = matches[0]
    body = match.group()
    if (body.count("    .locals 2\n") != 1
            or "->getLastPathSegment()Ljava/lang/String;" not in body
            or "Lmov/film/TvRows;->dir(Landroid/content/Context;)Ljava/io/File;" not in body
            or "Landroid/os/ParcelFileDescriptor;->open(Ljava/io/File;I)" not in body):
        raise ValueError("TvArt.openFile implementation changed; inspect before patching")
    hook = """
    invoke-virtual {p0}, Landroid/content/ContentProvider;->getContext()Landroid/content/Context;
    move-result-object v0
    invoke-static {v0, p1, p2}, Lmov/film/CleanArtwork;->openIfClean(Landroid/content/Context;Landroid/net/Uri;Ljava/lang/String;)Landroid/os/ParcelFileDescriptor;
    move-result-object v0
    if-eqz v0, :airhome_legacy_art
    return-object v0
    :airhome_legacy_art
"""
    body = body.replace("    .locals 2\n", "    .locals 2\n" + hook, 1)
    return text[:match.start()] + body + text[match.end():]


def certificate(apksigner, apk, env):
    info = run([apksigner, "verify", "--print-certs", apk], env)
    hashes = re.findall(r"Signer #\d+ certificate SHA-256 digest: ([0-9a-f]{64})", info)
    if len(hashes) != 1:
        raise ValueError("Expected exactly one APK signer")
    return hashes[0]


def signature_entry(name):
    return name == "META-INF/MANIFEST.MF" or bool(re.fullmatch(r"META-INF/[^/]+\.(SF|RSA|DSA|EC)", name, re.I))


def payload(apk):
    with zipfile.ZipFile(apk) as archive:
        return {name: hashlib.sha256(archive.read(name)).hexdigest()
                for name in archive.namelist()
                if not signature_entry(name) and not re.fullmatch(r"classes\d*\.dex", name)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path, help="Existing TV APK with airhome_badges metadata")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--apktool", type=Path, required=True)
    parser.add_argument("--sdk", type=Path, default=Path(os.environ.get("ANDROID_HOME", Path.home() / "Library/Android/sdk")))
    parser.add_argument("--java-home", type=Path, default=Path(os.environ.get("JAVA_HOME", "/Applications/Android Studio.app/Contents/jbr/Contents/Home")))
    parser.add_argument("--keystore", type=Path, default=Path.home() / ".android/debug.keystore")
    parser.add_argument("--alias", default="androiddebugkey")
    parser.add_argument("--ks-pass-file", type=Path)
    args = parser.parse_args()
    source, output = args.apk.resolve(), args.output.resolve()
    if source == output:
        raise ValueError("Input and output must differ")
    build = max((p for p in (args.sdk / "build-tools").iterdir() if (p / "apksigner").is_file()), key=lambda p: tuple(map(int, p.name.split("."))))
    android = next(p for p in (args.sdk / "platforms/android-35/android.jar", args.sdk / "platforms/android-37.0/android.jar") if p.exists())
    env = {**os.environ, "JAVA_HOME": str(args.java_home)}
    env["PATH"] = str(args.java_home / "bin") + os.pathsep + env.get("PATH", "")
    java = args.java_home / "bin/java"
    apktool = [java, "-jar", args.apktool.resolve()]
    signer = build / "apksigner"
    original_certificate = certificate(signer, source, env)
    with zipfile.ZipFile(source) as archive:
        if [n for n in archive.namelist() if re.fullmatch(r"classes\d*\.dex", n)] != ["classes.dex"]:
            raise ValueError("Expected the reviewed single-DEX film.mov build")
    if not args.keystore.is_file():
        raise ValueError("Existing signing key is required")
    if args.keystore != Path.home() / ".android/debug.keystore" and not args.ks_pass_file:
        raise ValueError("Custom signing key requires --ks-pass-file")
    with tempfile.TemporaryDirectory(prefix="film-clean-artwork-") as name:
        work = Path(name)
        decoded = work / "decoded"
        print("Decode and validate provider/cache contract...", flush=True)
        run(apktool + ["d", "--no-res", "-o", decoded, source], env)
        film = decoded / "smali/mov/film"
        if "airHomeMetadata" not in (film / "TvRows$Api26.smali").read_text():
            raise ValueError("Input APK lacks existing Air Home badge metadata")
        for cls in ("Tmdb", "GeMovieSource"):
            if ".method static lighter(Ljava/lang/String;I)Ljava/lang/String;" not in (film / f"{cls}.smali").read_text():
                raise ValueError(f"{cls}.lighter cache normalization changed")
        loader = (film / "ImageLoader.smali").read_text()
        for required in ('const-string v2, "posters"', 'const-string v0, "MD5"', 'const-string v2, "UTF-8"', '->getCacheDir()Ljava/io/File;', '->lighter(Ljava/lang/String;I)Ljava/lang/String;'):
            if required not in loader:
                raise ValueError("ImageLoader cache layout changed")
        provider = film / "TvArt.smali"
        provider.write_text(patch_provider(provider.read_text()))

        print("Run cache boundary regressions and compile helper...", flush=True)
        host = work / "host"
        host.mkdir()
        run([args.java_home / "bin/javac", "--release", "8", "-d", host, ROOT / "CleanPosterCache.java", ROOT / "CleanPosterCacheTest.java"], env)
        print(run([java, "-cp", host, "mov.film.CleanPosterCacheTest"], env).strip(), flush=True)
        classes, dex = work / "classes", work / "dex"
        classes.mkdir()
        dex.mkdir()
        stubs = []
        for cls in ("Tmdb", "GeMovieSource"):
            stub = work / f"{cls}.java"
            stub.write_text(f"package mov.film; final class {cls} {{ static String lighter(String u, int w) {{ return u; }} }}")
            stubs.append(stub)
        run([args.java_home / "bin/javac", "--release", "8", "-cp", android, "-d", classes, ROOT / "CleanArtwork.java", ROOT / "CleanPosterCache.java", *stubs], env)
        stubjar = work / "compile-only-stubs.jar"
        with zipfile.ZipFile(stubjar, "w") as archive:
            for cls in ("Tmdb", "GeMovieSource"):
                archive.write(classes / f"mov/film/{cls}.class", f"mov/film/{cls}.class")
        run([build / "d8", "--min-api", "24", "--lib", android, "--classpath", stubjar, "--output", dex,
             classes / "mov/film/CleanArtwork.class", classes / "mov/film/CleanPosterCache.class"], env)
        rebuilt = work / "rebuilt.apk"
        run(apktool + ["b", "-o", rebuilt, decoded], env)
        unsigned = work / "unsigned.apk"
        # Preserve original manifest/resources/assets byte-for-byte, replacing only code.
        with zipfile.ZipFile(source) as original, zipfile.ZipFile(rebuilt) as changed, zipfile.ZipFile(unsigned, "w") as result:
            for item in original.infolist():
                if signature_entry(item.filename):
                    continue
                data = changed.read("classes.dex") if item.filename == "classes.dex" else original.read(item.filename)
                result.writestr(item, data)
            result.write(dex / "classes.dex", "classes2.dex", compress_type=zipfile.ZIP_DEFLATED)
        aligned, signed = work / "aligned.apk", work / "signed.apk"
        run([build / "zipalign", "-P", "16", "-f", "4", unsigned, aligned], env)
        password = args.ks_pass_file
        if password is None:
            password = work / "debug-key-password"
            password.write_text("android\n")
            password.chmod(0o600)
        run([signer, "sign", "--ks", args.keystore, "--ks-key-alias", args.alias, "--ks-pass", "file:" + str(password), "--out", signed, aligned], env)
        if certificate(signer, signed, env) != original_certificate:
            raise ValueError("Signing certificate differs from source; upgrade refused")
        run([build / "zipalign", "-c", "-P", "16", "4", signed], env)
        before, after = payload(source), payload(signed)
        if before != after:
            raise ValueError("Non-code APK payload changed")
        report = dict(schema=1, contract="content://mov.film.tvart/clean?poster=<encoded original URL>",
                      source_sha256=hashlib.sha256(source.read_bytes()).hexdigest(),
                      output_sha256=hashlib.sha256(signed.read_bytes()).hexdigest(),
                      signer_sha256=original_certificate, unchanged_non_code_entries=len(before),
                      checks=["cache boundary host regressions", "same signer", "unchanged manifest/resources/assets", "zipalign 16K", "read-only cache route; no network"])
        output.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(signed, output)
        output.with_suffix(".json").write_text(json.dumps(report, indent=2) + "\n")
    print("Ready:", output)
    print("No applications installed. Verify the local cache route on the TV before release.")


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, RuntimeError, StopIteration) as error:
        raise SystemExit(str(error))
