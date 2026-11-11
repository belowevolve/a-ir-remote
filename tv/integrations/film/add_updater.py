#!/usr/bin/env python3
"""Embed a local update recipe and runtime into a personally signed film.mov APK.

No installation. Only a dedicated film.mov key may be embedded; generated signing
assets are private build artifacts and must never be committed or published.
"""
import argparse
import difflib
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import zipfile

from shade_runtime import runtime_jar
from patch_clean_artwork import signature_entry

ROOT = Path(__file__).resolve().parent
PROJECT = ROOT.parents[2]
JAVA = Path(os.environ.get('JAVA_HOME', '/Applications/Android Studio.app/Contents/jbr/Contents/Home')) / 'bin'
SDK = Path(os.environ.get('ANDROID_HOME', Path.home() / 'Library/Android/sdk'))
BUILD = SDK / 'build-tools/36.0.0'
PROTECTED_SIGNERS = {
    'eb268bd67e9c38a368e58479f4ebcbaab107000d2626477e171eb642bee83f1d',  # Air Remote
    'e3e954c05fd51cc6a5b04752f6cbcb0804258e61ea98b3c0a9e9a78b313e9d12',  # Air Home
}


def run(args):
    subprocess.run([str(x) for x in args], check=True)


def normalized(text):
    lines = []
    for line in text.splitlines():
        quoted = escaped = False
        for i, char in enumerate(line):
            if escaped:
                escaped = False
                continue
            if char == chr(92):
                escaped = True
                continue
            if char == '"':
                quoted = not quoted
            if char == '#' and not quoted:
                line = line[:i]
                break
        line = line.strip()
        if line and not line.startswith(('.line ', '.source ')):
            lines.append(line + '\n')
    return ''.join(lines)


def hunks(before, after):
    a, b = before.splitlines(keepends=True), after.splitlines(keepends=True)
    result = []
    for group in difflib.SequenceMatcher(None, a, b, autojunk=False).get_grouped_opcodes(4):
        i, j = group[0][1], group[-1][2]
        x, y = group[0][3], group[-1][4]
        old = ''.join(a[i:j]); new = ''.join(b[x:y])
        # Extend context until it identifies exactly one place in the original.
        while before.count(old) != 1:
            if i > 0 and x > 0:
                i -= 1; x -= 1
            elif j < len(a) and y < len(b):
                j += 1; y += 1
            else:
                raise ValueError('Ambiguous patch context')
            old, new = ''.join(a[i:j]), ''.join(b[x:y])
        result.append(dict(before=old, after=new))
    # Expanded contexts may overlap; validate against sequential application.
    current = before
    for h in result:
        if current.count(h['before']) != 1:
            # Large/ambiguous edits use a guarded class replacement.
            return [dict(before=before, after=after)]
        current = current.replace(h['before'], h['after'], 1)
    assert current == after
    return result


def validate_film_key(keystore, alias, password_file):
    certificate = subprocess.check_output([str(JAVA / 'keytool'), '-exportcert',
        '-keystore', str(keystore.resolve()), '-alias', alias,
        '-storepass:file', str(password_file.resolve())])
    if hashlib.sha256(certificate).hexdigest() in PROTECTED_SIGNERS:
        raise ValueError('Refusing to embed an Air Remote / Air Home production key in film.mov')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('original', type=Path)
    parser.add_argument('patched', type=Path)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--keystore', type=Path, required=True, help='Dedicated film.mov keystore; its private key is embedded')
    parser.add_argument('--alias', required=True)
    parser.add_argument('--ks-pass-file', type=Path, required=True)
    args = parser.parse_args()
    if args.output.resolve() in (args.original.resolve(), args.patched.resolve()):
        raise ValueError('Input and output APK paths must differ')
    validate_film_key(args.keystore, args.alias, args.ks_pass_file)
    module = ROOT / 'updater'
    env = dict(os.environ, JAVA_HOME=str(JAVA.parent))
    subprocess.run([str(PROJECT / 'gradlew'), '-p', str(module), 'compileJava', 'runtimeJars', '--console=plain'], env=env, check=True)
    jars = sorted((module / 'build/runtime').glob('*.jar'))
    # org.json is supplied by Android; the host recipe tool uses its Maven implementation.
    host_json = PROJECT / 'artifacts/film-updater-tools/json.jar'
    if not host_json.exists():
        import urllib.request
        host_json.parent.mkdir(parents=True, exist_ok=True)
        urllib.request.urlretrieve('https://repo.maven.apache.org/maven2/org/json/json/20240303/json-20240303.jar', host_json)
    classes = module / 'build/classes/java/main'
    cp = os.pathsep.join(str(p) for p in [classes, host_json, *jars])
    helper = [JAVA / 'java', '-Xmx1536m', '-cp', cp, 'mov.film.airupdate.PatchEngine']
    with tempfile.TemporaryDirectory(prefix='film-local-updater-') as tmp:
        work = Path(tmp)
        original, patched = work / 'original', work / 'patched'
        run(helper + ['decode', args.original.resolve(), original])
        run(helper + ['decode', args.patched.resolve(), patched])
        settings = patched / 'mov/film/SettingsActivity.smali'
        text = normalized(settings.read_text())
        hook = '\ninvoke-static/range {p0 .. p0}, Lmov/film/airupdate/PatchedUpdate;->addRow(Landroid/app/Activity;)V\n'
        text, count = re.subn(r'(\.method private appRows\(\)V\n\.registers \d+\n)', lambda m: m[0] + hook, text)
        if count != 1 or 'row(Ljava/lang/CharSequence;Ljava/lang/String;Ljava/lang/String;Ljava/lang/Runnable;)Landroid/view/View;' not in text:
            raise ValueError('Settings row contract changed')
        settings.write_text(text)
        update = patched / 'mov/film/Update.smali'
        text = normalized(update.read_text())
        signature = 'install(Landroid/content/Context;Lmov/film/Update$Found;Lmov/film/Update$Progress;)Ljava/lang/String;'
        replacement = '.method public static ' + signature + '''
.locals 1
invoke-static {p0, p1, p2}, Lmov/film/airupdate/PatchedUpdate;->nativeInstall(Landroid/content/Context;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/String;
move-result-object v0
return-object v0
.end method'''
        text, count = re.subn(r'(?ms)^\.method public static ' + re.escape(signature) + r'\n.*?^\.end method', lambda m: replacement, text)
        if count != 1 or 'listen(Landroid/content/Context;)V' not in text or 'confirm(Landroid/content/Context;I)Landroid/app/PendingIntent;' not in text:
            raise ValueError('Native installation callback contract changed')
        update.write_text(text)
        # Assemble the recipe's application code. The updater/dependencies live in their own DEX.
        smali = [JAVA / 'java', '-Xmx1536m', '-cp', cp, 'org.jf.smali.Main']
        run(smali + ['assemble', '--api', '28', '-o', work / 'application.dex', patched])
        runtime = work / 'runtime'; runtime.mkdir()
        shaded = work / 'runtime.jar'
        runtime_jar(classes, jars, shaded)
        digest = hashlib.sha256(shaded.read_bytes()).hexdigest()
        cached = module / 'build/runtime-dex' / (digest + '.dex')
        if cached.exists():
            (runtime / 'classes.dex').write_bytes(cached.read_bytes())
        else:
            run([BUILD / 'd8', '--min-api', '28', '--lib', SDK / 'platforms/android-35/android.jar', '--output', runtime, shaded])
            cached.parent.mkdir(parents=True, exist_ok=True)
            cached.write_bytes((runtime / 'classes.dex').read_bytes())
        dex_files = list(runtime.glob('*.dex'))
        if len(dex_files) != 1:
            raise ValueError('Expected one runtime DEX')
        recipe = dict(schema=1, code=[], files=[])
        recipe_path = work / 'recipe.zip'
        with zipfile.ZipFile(recipe_path, 'w', compression=zipfile.ZIP_DEFLATED) as out:
            for p in sorted(patched.rglob('*.smali')):
                name = p.relative_to(patched).as_posix()
                after = normalized(p.read_text()); src = original / name
                if src.exists():
                    before = normalized(src.read_text())
                    if before != after: recipe['code'].append(dict(path=name, hunks=hunks(before, after)))
                else:
                    added = 'added/' + name
                    out.writestr(added, after)
                    recipe['code'].append(dict(path=name, added=added))
            with zipfile.ZipFile(args.original) as before, zipfile.ZipFile(args.patched) as after:
                for source, key in [(before, 'original'), (after, 'patched')]:
                    out.writestr(key + '.xml', source.read('AndroidManifest.xml'))
                    out.writestr(key + '.arsc', source.read('resources.arsc'))
                for name in after.namelist():
                    if name in ('AndroidManifest.xml', 'resources.arsc') or re.fullmatch(r'classes\d*\.dex', name) or signature_entry(name) or name.endswith('/'):
                        continue
                    old = before.read(name) if name in before.namelist() else None
                    new = after.read(name)
                    if old != new:
                        recipe['files'].append(dict(path=name, before=hashlib.sha256(old).hexdigest() if old is not None else ''))
                        out.writestr('files/' + name, new)
            out.writestr('recipe.json', json.dumps(recipe, ensure_ascii=False))
        print('Recipe:', len(recipe['code']), 'classes;', len(recipe['files']), 'resource files', flush=True)
        assets = work / 'assets'; assets.mkdir(mode=0o700)
        run(helper + ['key', args.keystore.resolve(), assets, args.alias, args.ks_pass_file.resolve()])
        if hashlib.sha256((assets / 'signer.der').read_bytes()).hexdigest() in PROTECTED_SIGNERS:
            raise ValueError('Refusing to embed an Air Remote / Air Home production key in film.mov')
        unsigned = work / 'unsigned.apk'
        with zipfile.ZipFile(args.patched) as src, zipfile.ZipFile(unsigned, 'w', compression=zipfile.ZIP_DEFLATED) as out:
            for item in src.infolist():
                if re.fullmatch(r'classes\d*\.dex', item.filename) or signature_entry(item.filename):
                    continue
                out.writestr(item, src.read(item.filename))
            out.write(work / 'application.dex', 'classes.dex')
            out.write(dex_files[0], 'classes2.dex')
            out.write(dex_files[0], 'assets/airupdate/runtime.dex')
            out.write(recipe_path, 'assets/airupdate/recipe.zip')
            for name in ['signer.pk8', 'signer.der']:
                out.write(assets / name, 'assets/airupdate/' + name)
        aligned = work / 'aligned.apk'
        run([BUILD / 'zipalign', '-P', '16', '-f', '4', unsigned, aligned])
        args.output.parent.mkdir(parents=True, exist_ok=True)
        run([BUILD / 'apksigner', 'sign', '--ks', args.keystore.resolve(), '--ks-key-alias', args.alias, '--ks-pass', 'file:' + str(args.ks_pass_file.resolve()), '--out', args.output.resolve(), aligned])
        run([BUILD / 'apksigner', 'verify', args.output.resolve()])
        run([BUILD / 'zipalign', '-c', '-P', '16', '4', args.output.resolve()])
    print('Ready:', args.output)


if __name__ == '__main__':
    main()
