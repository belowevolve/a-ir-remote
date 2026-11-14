#!/usr/bin/env python3
"""Release inactive TV video resources and restore them on the real display surface.

Applies only to the verified T31 firmware at runtime. Preserves the original
track selection, PiP, APK resources and the embedded updater recipe.
"""
import argparse
import io
import json
from pathlib import Path
import re
import subprocess
import tempfile
import zipfile

from add_updater import hunks, normalized
from patch_badges import dex_classes
from patch_clean_artwork import signature_entry

ROOT = Path(__file__).resolve().parent
PLAYER = 'Lmov/film/PlayerActivity;'
RENDERER = 'Landroidx/media3/exoplayer/video/MediaCodecVideoRenderer;'
CALLBACK = 'Lmov/film/airvideo/SurfaceRestore;'


def method(text, name, transform):
    matches = list(re.finditer(r'(?ms)^\.method [^\n]* ' + re.escape(name) + r'\(\)V\n.*?^\.end method', text))
    if len(matches) != 1:
        raise ValueError('Missing or ambiguous method: ' + name)
    m = matches[0]
    return text[:m.start()] + transform(m.group()) + text[m.end():]


def player_patch(text):
    if 'airStopped' not in text or 'airVideoParameters' in text:
        raise ValueError('Expected the buffering lifecycle guard, without the video lifecycle patch')
    original = text
    anchor = '# instance fields\n'
    if text.count(anchor) != 1:
        raise ValueError('Unexpected player fields')
    text = text.replace(anchor, anchor + '.field private airVideoParameters:Landroidx/media3/common/TrackSelectionParameters;\n.field private airVideoCallback:Lmov/film/airvideo/SurfaceRestore;\n', 1)

    def start(body):
        anchor = '    return-void'
        if body.count(anchor) != 1:
            raise ValueError('Unexpected onStart exits')
        return body.replace(anchor, '    invoke-direct/range {p0 .. p0}, ' + PLAYER + '->airRestoreVideo()V\n\n' + anchor)

    def stop(body):
        anchor = '    invoke-super {p0}, Landroid/app/Activity;->onStop()V'
        if body.count(anchor) != 1:
            raise ValueError('Unexpected onStop superclass')
        return body.replace(anchor, anchor + '\n\n    invoke-direct/range {p0 .. p0}, ' + PLAYER + '->airSuspendVideo()V')

    text = method(method(text, 'onStart', start), 'onStop', stop)
    text += '\n' + (ROOT / 'video_lifecycle.smali').read_text().replace('@TYPE@', PLAYER)
    return text, hunks(normalized(original), normalized(text))


def renderer_patch(text):
    original = text

    def disable(body):
        anchor = '    invoke-super {p0}, Landroidx/media3/exoplayer/mediacodec/MediaCodecRenderer;->onDisabled()V'
        if body.count(anchor) != 1 or 'air_release_done' in body:
            raise ValueError('Unexpected renderer lifecycle')
        registers = re.search(r'\.registers (\d+)', body)
        if not registers or int(registers[1]) < 2:
            raise ValueError('No renderer scratch register')
        return body.replace(anchor, anchor + '''

    invoke-static {}, Lmov/film/airvideo/SurfaceRestore;->supported()Z
    move-result v0
    if-eqz v0, :air_release_done
    invoke-virtual {p0}, Landroidx/media3/exoplayer/mediacodec/MediaCodecRenderer;->releaseCodec()V
    :air_release_done''')

    text = method(text, 'onDisabled', disable)
    return text, hunks(normalized(original), normalized(text))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('apk', type=Path)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--runtime', type=Path, default=ROOT / 'updater/build/runtime')
    parser.add_argument('--keystore', type=Path, required=True)
    parser.add_argument('--alias', required=True)
    parser.add_argument('--ks-pass-file', type=Path, required=True)
    args = parser.parse_args()
    if args.apk.resolve() == args.output.resolve():
        raise ValueError('Separate output required')
    sdk = Path.home() / 'Library/Android/sdk'
    build = sdk / 'build-tools/36.0.0'
    cp = str(args.runtime.resolve() / '*')

    def run(*args):
        subprocess.run([str(a) for a in args], check=True)

    with tempfile.TemporaryDirectory(prefix='film-video-lifecycle-') as tmp, zipfile.ZipFile(args.apk) as source:
        work = Path(tmp)
        run('javac', '-cp', cp, '-d', work, ROOT / 'FilmDexClass.java')
        java = ['java', '-cp', str(work) + ':' + cp, 'FilmDexClass']
        dex_names = [n for n in source.namelist() if re.fullmatch(r'classes\d*\.dex', n)]
        classes = {n: dex_classes(source.read(n)) for n in dex_names}
        if any(CALLBACK in c for c in classes.values()):
            raise ValueError('Surface callback already present')
        replacements = {}
        deltas = []
        for index, (kind, transform) in enumerate([(PLAYER, player_patch), (RENDERER, renderer_patch)]):
            names = [n for n, types in classes.items() if kind in types]
            if len(names) != 1:
                raise ValueError('Missing or duplicated class: ' + kind)
            name = names[0]
            original = work / f'original-{index}.dex'
            original.write_bytes(replacements.get(name, source.read(name)))
            smali = work / f'smali-{index}'
            run(*java, 'decode', original, smali, kind)
            relative = kind[1:-1] + '.smali'
            path = smali / relative
            text, delta = transform(path.read_text())
            path.write_text(text)
            patch = work / f'patch-{index}.dex'
            merged = work / f'merged-{index}.dex'
            run('java', '-cp', cp, 'org.jf.smali.Main', 'assemble', smali, '-o', patch)
            run(*java, 'merge', original, patch, merged, kind)
            replacements[name] = merged.read_bytes()
            deltas.append((relative, delta))

        callback_classes = work / 'callback-classes'
        callback_dex = work / 'callback-dex'
        callback_classes.mkdir()
        callback_dex.mkdir()
        run('javac', '--release', '8', '-cp', sdk / 'platforms/android-35/android.jar', '-d', callback_classes, ROOT / 'SurfaceRestore.java')
        run(build / 'd8', '--min-api', '28', '--output', callback_dex, *sorted(callback_classes.rglob('*.class')))
        callback = callback_dex / 'classes.dex'
        if set(dex_classes(callback.read_bytes())) != {CALLBACK}:
            raise ValueError('Unexpected callback classes')
        callback_smali = work / 'callback-smali'
        run(*java, 'decode', callback, callback_smali, CALLBACK)
        added_path = CALLBACK[1:-1] + '.smali'
        added_text = normalized((callback_smali / added_path).read_text())

        recipe_name = 'assets/airupdate/recipe.zip'
        recipe = None
        if recipe_name in source.namelist():
            buffer = io.BytesIO()
            with zipfile.ZipFile(io.BytesIO(source.read(recipe_name))) as old, zipfile.ZipFile(buffer, 'w', zipfile.ZIP_DEFLATED) as new:
                config = json.loads(old.read('recipe.json'))
                if any(c['path'] == added_path for c in config['code']):
                    raise ValueError('Callback already in update recipe')
                for path, delta in deltas:
                    entry = next((c for c in config['code'] if c['path'] == path), None)
                    if entry is None:
                        entry = {'path': path, 'hunks': []}
                        config['code'].append(entry)
                    if 'added' in entry:
                        raise ValueError('Cannot patch an added upstream class')
                    entry['hunks'].extend(delta)
                added = 'added/' + added_path
                config['code'].append({'path': added_path, 'added': added})
                for item in old.infolist():
                    new.writestr(item, json.dumps(config, ensure_ascii=False).encode() if item.filename == 'recipe.json' else old.read(item))
                new.writestr(added, added_text)
            recipe = buffer.getvalue()

        number = max(1 if n == 'classes.dex' else int(re.search(r'\d+', n)[0]) for n in dex_names) + 1
        with zipfile.ZipFile(work / 'unsigned.apk', 'w', zipfile.ZIP_DEFLATED) as out:
            for item in source.infolist():
                if signature_entry(item.filename):
                    continue
                data = replacements.get(item.filename)
                if data is None:
                    data = recipe if item.filename == recipe_name and recipe is not None else source.read(item)
                out.writestr(item, data)
            out.write(callback, f'classes{number}.dex')
        run(build / 'zipalign', '-P', '16', '-f', '4', work / 'unsigned.apk', work / 'aligned.apk')
        args.output.parent.mkdir(parents=True, exist_ok=True)
        run(build / 'apksigner', 'sign', '--ks', args.keystore, '--ks-key-alias', args.alias, '--ks-pass', 'file:' + str(args.ks_pass_file), '--out', args.output, work / 'aligned.apk')
        run(build / 'apksigner', 'verify', args.output)
        run(build / 'zipalign', '-c', '-P', '16', '4', args.output)
        print('Video lifecycle and embedded update recipe: patched')


if __name__ == '__main__':
    main()
