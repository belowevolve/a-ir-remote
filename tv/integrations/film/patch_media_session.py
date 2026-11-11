#!/usr/bin/env python3
"""Publish film.mov's foreground player through Android's standard MediaSession."""
import argparse
import io
import json
from pathlib import Path
import re
import subprocess
import tempfile
import zipfile
from patch_badges import dex_classes
from patch_clean_artwork import signature_entry
from add_updater import normalized, hunks

ROOT = Path(__file__).resolve().parent
TYPE = 'Lmov/film/PlayerActivity;'
BRIDGE = 'Lmov/film/airvoice/FilmMediaSession;'
PREFIXES = ('Lmov/film/airvoice/FilmMediaSession',)


def helper(value):
    return value.startswith(PREFIXES)


def patch(text):
    original = text
    if BRIDGE not in text:
        def hook(match):
            body, method = match.group(0), match.group(1)
            anchor = 'invoke-super {p0}, Landroid/app/Activity;->' + method + '()V'
            if body.count(anchor) != 1: raise ValueError('Lifecycle contract changed')
            call = '\ninvoke-static/range {p0 .. p0}, ' + BRIDGE + '->' + ('start' if method == 'onStart' else 'stop') + '(Landroid/app/Activity;)V'
            return body.replace(anchor, anchor + call, 1)
        text, count = re.subn(r'(?ms)^\.method protected (onStart|onStop)\(\)V\n.*?^\.end method', hook, text)
        if count != 2: raise ValueError('Lifecycle methods missing')
    if '->sync(Landroid/app/Activity;)V' not in text:
        def assigned(match):
            owner = match.group(1)
            return match.group(0) + '\ninvoke-static/range {' + owner + ' .. ' + owner + '}, ' + BRIDGE + '->sync(Landroid/app/Activity;)V'
        text, count = re.subn(r'iput-object \w+, (\w+), Lmov/film/PlayerActivity;->player:Landroidx/media3/exoplayer/ExoPlayer;', assigned, text)
        if count < 1: raise ValueError('Player ownership field changed')
    return text, hunks(normalized(original), normalized(text))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('apk', type=Path)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--keystore', type=Path, required=True)
    parser.add_argument('--alias', required=True)
    parser.add_argument('--ks-pass-file', type=Path, required=True)
    args = parser.parse_args()
    if args.apk.resolve() == args.output.resolve(): raise ValueError('Separate output required')
    sdk = Path.home() / 'Library/Android/sdk'
    build = sdk / 'build-tools/36.0.0'
    android = sdk / 'platforms/android-35/android.jar'
    cp = str(ROOT / 'updater/build/runtime/*')
    def run(*argv): subprocess.run([str(x) for x in argv], check=True)
    with tempfile.TemporaryDirectory(prefix='film-media-session-') as tmp:
        work = Path(tmp)
        run('javac', '-cp', cp, '-d', work, ROOT / 'FilmDexClass.java')
        java = ['java', '-cp', str(work) + ':' + cp, 'FilmDexClass']
        classes = work / 'classes'; classes.mkdir()
        run('javac', '--release', '8', '-cp', android, '-d', classes, ROOT / 'FilmMediaSession.java')
        run('jar', 'cf', work / 'bridge.jar', '-C', classes, '.')
        bridge = work / 'bridge'; bridge.mkdir()
        run(build / 'd8', '--min-api', '24', '--lib', android, '--output', bridge, work / 'bridge.jar')
        run('java', '-cp', cp, 'org.jf.baksmali.Main', 'disassemble', bridge / 'classes.dex', '-o', work / 'bridge-smali')
        with zipfile.ZipFile(args.apk) as source:
            dex_names = [n for n in source.namelist() if re.fullmatch(r'classes\d*\.dex', n)]
            types = {name: dex_classes(source.read(name)) for name in dex_names}
            names = [n for n in dex_names if TYPE in types[n]]
            if len(names) != 1: raise ValueError('Missing or duplicate player class')
            name = names[0]; dex = work / 'original.dex'; dex.write_bytes(source.read(name))
            run(*java, 'decode', dex, work / 'smali')
            player = work / 'smali/mov/film/PlayerActivity.smali'
            text, delta = patch(player.read_text()); player.write_text(text)
            run('java', '-cp', cp, 'org.jf.smali.Main', 'assemble', work / 'smali', '-o', work / 'player.dex')
            replacements = {}
            pure_helpers = [n for n in dex_names if types[n] and all(helper(t) for t in types[n])]
            if len(pure_helpers) > 1: raise ValueError('Unexpected split helper DEX layout')
            destination = pure_helpers[0] if pure_helpers else f'classes{max([1 if n == "classes.dex" else int(n[7:-4]) for n in dex_names]) + 1}.dex'
            for index, current in enumerate(dex_names):
                if current in pure_helpers: continue
                if current == name or any(helper(t) for t in types[current]):
                    original = work / f'original-{index}.dex'; original.write_bytes(source.read(current))
                    merged = work / f'merged-{index}.dex'
                    run(*java, 'media', original, work / 'player.dex' if current == name else '-', merged)
                    replacements[current] = merged.read_bytes()
            recipe_name = 'assets/airupdate/recipe.zip'; recipe = None
            if recipe_name in source.namelist():
                buffer = io.BytesIO()
                def old_helper_path(path):
                    return path.startswith('mov/film/airvoice/FilmMediaSession')
                with zipfile.ZipFile(io.BytesIO(source.read(recipe_name))) as old, zipfile.ZipFile(buffer, 'w', zipfile.ZIP_DEFLATED) as new:
                    config = json.loads(old.read('recipe.json'))
                    config['code'] = [c for c in config['code'] if not old_helper_path(c['path'])]
                    entry = next((c for c in config['code'] if c['path'] == 'mov/film/PlayerActivity.smali'), None)
                    if entry is None:
                        entry = {'path': 'mov/film/PlayerActivity.smali', 'hunks': []}; config['code'].append(entry)
                    entry['hunks'].extend(delta)
                    for path in sorted((work / 'bridge-smali').rglob('*.smali')):
                        relative = path.relative_to(work / 'bridge-smali').as_posix(); added = 'added/' + relative
                        config['code'].append({'path': relative, 'added': added})
                        new.writestr(added, normalized(path.read_text()))
                    for item in old.infolist():
                        if item.filename.startswith('added/') and old_helper_path(item.filename[6:]): continue
                        new.writestr(item, json.dumps(config, ensure_ascii=False).encode() if item.filename == 'recipe.json' else old.read(item))
                recipe = buffer.getvalue()
            with zipfile.ZipFile(work / 'unsigned.apk', 'w', zipfile.ZIP_DEFLATED) as out:
                for item in source.infolist():
                    if signature_entry(item.filename) or item.filename == destination: continue
                    data = replacements.get(item.filename, recipe if item.filename == recipe_name and recipe is not None else source.read(item))
                    out.writestr(item, data)
                out.write(bridge / 'classes.dex', destination)
        run(build / 'zipalign', '-P', '16', '-f', '4', work / 'unsigned.apk', work / 'aligned.apk')
        args.output.parent.mkdir(parents=True, exist_ok=True)
        run(build / 'apksigner', 'sign', '--ks', args.keystore, '--ks-key-alias', args.alias, '--ks-pass', 'file:' + str(args.ks_pass_file), '--out', args.output, work / 'aligned.apk')
        run(build / 'apksigner', 'verify', args.output)
        print('Standard MediaSession, player replacement tracking, lifecycle and embedded recipe: patched')


if __name__ == '__main__': main()
