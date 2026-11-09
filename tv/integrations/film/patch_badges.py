#!/usr/bin/env python3
"""Apply Air Home's season-segment badge to an existing, locally signed film.mov APK."""
import argparse
import os
from pathlib import Path
import re
import shutil
import struct
import tempfile
import zipfile

from patch_clean_artwork import certificate, payload, run, signature_entry

ROOT = Path(__file__).resolve().parent


ADAPTER = "Lmov/film/PosterBadgeStyle;"
SPEC = "Lbelowevolve/shared/design/PosterBadgeSpec;"


def dex_classes(data):
    """Read class definitions, rather than references to the adapter from another DEX."""
    if not data.startswith(b"dex\n"):
        raise ValueError("Unsupported DEX container")
    strings = struct.unpack_from("<I", data, 60)[0]
    types = struct.unpack_from("<I", data, 68)[0]
    count, classes = struct.unpack_from("<II", data, 96)
    result = set()
    for index in range(count):
        class_id = struct.unpack_from("<I", data, classes + 32 * index)[0]
        string_id = struct.unpack_from("<I", data, types + 4 * class_id)[0]
        start = struct.unpack_from("<I", data, strings + 4 * string_id)[0]
        while data[start] & 128:
            start += 1
        start += 1
        result.add(data[start:data.index(0, start)].decode("utf-8", errors="surrogatepass"))
    return result


def adapter_dex(apk):
    found = []
    with zipfile.ZipFile(apk) as archive:
        for name in archive.namelist():
            if not re.fullmatch(r"classes\d*\.dex", name):
                continue
            classes = dex_classes(archive.read(name))
            if ADAPTER in classes:
                if SPEC not in classes or any(c != SPEC and c != ADAPTER and not c.startswith(ADAPTER[:-1] + "$") for c in classes):
                    raise ValueError("Adapter DEX also contains unrelated application classes")
                found.append(name)
    if len(found) > 1:
        raise ValueError("Duplicate badge adapters")
    return found[0] if found else None


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--apktool", type=Path, help="Required only for APKs without the badge adapter")
    parser.add_argument("--sdk", type=Path, default=Path.home() / "Library/Android/sdk")
    parser.add_argument("--java-home", type=Path, default=Path("/Applications/Android Studio.app/Contents/jbr/Contents/Home"))
    parser.add_argument("--keystore", type=Path, default=Path.home() / ".android/debug.keystore")
    parser.add_argument("--alias", default="androiddebugkey")
    parser.add_argument("--ks-pass-file", type=Path)
    args = parser.parse_args()
    source = args.apk.resolve()
    build = max((p for p in (args.sdk / "build-tools").iterdir() if (p / "apksigner").exists()), key=lambda p: tuple(map(int, p.name.split('.'))))
    android = args.sdk / "platforms/android-35/android.jar"
    env = {**os.environ, "JAVA_HOME": str(args.java_home)}
    java = args.java_home / "bin/java"
    apktool = [java, "-jar", args.apktool.resolve()] if args.apktool else None
    signer = build / "apksigner"
    original = certificate(signer, source, env)
    with tempfile.TemporaryDirectory(prefix="film-badges-") as folder:
        work = Path(folder)
        adapter = adapter_dex(source)
        decoded = work / "decoded"
        if adapter is None:
            if apktool is None:
                raise ValueError("An unpatched APK requires --apktool")
            run(apktool + ["d", "--no-res", "-o", decoded, source], env)
            film = decoded / "smali/mov/film"
            path = film / "SourcePick.smali"
            text = path.read_text()
            signature = "corner(Landroid/content/Context;)Landroid/view/ViewGroup;"
            match = re.search(r"(?ms)^\.method public static " + re.escape(signature) + r"\n.*?^\.end method", text)
            if not match or "badgeFixed" not in match[0] or "Landroid/widget/TextView;" not in match[0]:
                raise ValueError("Unsupported poster badge layout")
            for cls, method in [("Type", "badgeFixed(Landroid/widget/TextView;)V"), ("DynamicTheme", "color(I)I")]:
                if method not in (film / (cls + ".smali")).read_text():
                    raise ValueError("Required film.mov typography/palette adapter missing")
            path.write_text(text[:match.start()] + ".method public static " + signature + "\n    .locals 1\n    invoke-static {p0}, Lmov/film/PosterBadgeStyle;->create(Landroid/content/Context;)Landroid/view/ViewGroup;\n    move-result-object v0\n    return-object v0\n.end method" + text[match.end():])
        classes, dex = work / "classes", work / "dex"
        classes.mkdir(); dex.mkdir()
        stubs = {
            "Type": "static void badgeFixed(android.widget.TextView v) {}",
            "DynamicTheme": "static int color(int c) { return c; }",
        }
        stub_sources = []
        for name, body in stubs.items():
            stub = work / (name + ".java")
            stub.write_text("package mov.film; final class " + name + " { " + body + " }")
            stub_sources.append(stub)
        spec = ROOT.parents[2] / "shared/src/main/java/belowevolve/shared/design/PosterBadgeSpec.java"
        run([args.java_home / "bin/javac", "--release", "8", "-cp", android, "-d", classes, spec, ROOT / "PosterBadgeStyle.java", *stub_sources], env)
        stubjar = work / "stubs.jar"
        excluded = {"mov/film/" + name + ".class" for name in stubs}
        with zipfile.ZipFile(stubjar, "w") as z:
            for name in excluded: z.write(classes / name, name)
        program = [p for p in classes.rglob("*.class") if p.relative_to(classes).as_posix() not in excluded]
        run([build / "d8", "--min-api", "24", "--lib", android, "--classpath", stubjar, "--output", dex, *program], env)
        replacement = (dex / "classes.dex").read_bytes()
        if adapter is not None:
            with zipfile.ZipFile(source) as old:
                if dex_classes(replacement) != dex_classes(old.read(adapter)):
                    raise ValueError("Updating the adapter would change its class contract")
        else:
            rebuilt = work / "rebuilt.apk"
            run(apktool + ["b", "-o", rebuilt, decoded], env)
        unsigned = work / "unsigned.apk"
        with zipfile.ZipFile(source) as old, zipfile.ZipFile(unsigned, "w") as out:
            dex_names = [n for n in old.namelist() if re.fullmatch(r"classes\d*\.dex", n)]
            next_dex = max(int(re.search(r"classes(\d*)", n)[1] or "1") for n in dex_names) + 1
            for entry in old.infolist():
                if signature_entry(entry.filename):
                    continue
                data = old.read(entry.filename)
                if adapter == entry.filename:
                    data = replacement
                elif adapter is None and entry.filename == "classes.dex":
                    with zipfile.ZipFile(rebuilt) as new:
                        data = new.read("classes.dex")
                out.writestr(entry, data)
            if adapter is None:
                out.writestr(f"classes{next_dex}.dex", replacement, compress_type=zipfile.ZIP_DEFLATED)
        aligned, signed = work / "aligned.apk", work / "signed.apk"
        run([build / "zipalign", "-P", "16", "-f", "4", unsigned, aligned], env)
        password = args.ks_pass_file
        if password is None:
            if args.keystore != Path.home() / ".android/debug.keystore":
                raise ValueError("Custom signing key requires --ks-pass-file")
            password = work / "password"
            password.write_text("android\n"); password.chmod(0o600)
        run([signer, "sign", "--ks", args.keystore, "--ks-key-alias", args.alias, "--ks-pass", "file:" + str(password), "--out", signed, aligned], env)
        if certificate(signer, signed, env) != original or payload(signed) != payload(source):
            raise ValueError("Signer or non-code APK payload changed")
        if adapter is not None:
            with zipfile.ZipFile(source) as old, zipfile.ZipFile(signed) as new:
                for name in dex_names:
                    if name != adapter and old.read(name) != new.read(name):
                        raise ValueError("Unrelated application code changed: " + name)
        run([build / "zipalign", "-c", "-P", "16", "4", signed], env)
        args.output.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(signed, args.output)
    print("Ready (same signer, resources and data contract):", args.output)


if __name__ == "__main__":
    main()
