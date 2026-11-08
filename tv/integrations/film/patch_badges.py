#!/usr/bin/env python3
"""Apply Air Home's compact badge format to an existing, locally signed film.mov APK."""
import argparse
import os
from pathlib import Path
import re
import shutil
import tempfile
import zipfile

from patch_clean_artwork import certificate, payload, run, signature_entry

ROOT = Path(__file__).resolve().parent


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--apktool", type=Path, required=True)
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
    apktool = [java, "-jar", args.apktool.resolve()]
    signer = build / "apksigner"
    original = certificate(signer, source, env)
    with tempfile.TemporaryDirectory(prefix="film-badges-") as folder:
        work = Path(folder)
        decoded = work / "decoded"
        run(apktool + ["d", "--no-res", "-o", decoded, source], env)
        film = decoded / "smali/mov/film"
        path = film / "SourcePick.smali"
        text = path.read_text()
        if "Lmov/film/PosterBadgeStyle;" in text:
            raise ValueError("Compact badges are already applied")
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
        rebuilt = work / "rebuilt.apk"
        run(apktool + ["b", "-o", rebuilt, decoded], env)
        unsigned = work / "unsigned.apk"
        with zipfile.ZipFile(source) as old, zipfile.ZipFile(rebuilt) as new, zipfile.ZipFile(unsigned, "w") as out:
            dex_names = [n for n in old.namelist() if re.fullmatch(r"classes\d*\.dex", n)]
            next_dex = max(int(re.search(r"classes(\d*)", n)[1] or "1") for n in dex_names) + 1
            for entry in old.infolist():
                if not signature_entry(entry.filename):
                    out.writestr(entry, new.read("classes.dex") if entry.filename == "classes.dex" else old.read(entry.filename))
            out.write(dex / "classes.dex", f"classes{next_dex}.dex", compress_type=zipfile.ZIP_DEFLATED)
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
        run([build / "zipalign", "-c", "-P", "16", "4", signed], env)
        args.output.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(signed, args.output)
    print("Ready (same signer, resources and data contract):", args.output)


if __name__ == "__main__":
    main()
