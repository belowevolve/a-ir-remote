#!/usr/bin/env python3
"""Pause a buffering film.mov player on STOP and reject late background autoplay.

Keeps PiP, resources, signer and any embedded update recipe. Fails closed if
upstream's expected lifecycle or play call structure has changed.
"""
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


def patch(text):
    if 'airStopped' in text:
        raise ValueError('Player lifecycle patch is already present')
    original = text
    text = text.replace('.field ', '.field private airStopped:Z\n\n.field ', 1)
    def lifecycle(match):
        body = match.group(0)
        method = match.group(1)
        anchor = 'invoke-super {p0}, Landroid/app/Activity;->' + method + '()V'
        assert body.count(anchor) == 1
        value = '0x1' if method == 'onStop' else '0x0'
        body = body.replace(anchor, anchor + '\nconst/4 v0, ' + value + '\niput-boolean v0, p0, ' + TYPE + '->airStopped:Z', 1)
        if method == 'onStop':
            guard = re.compile(r'iget-boolean v1, p0, Lmov/film/PlayerActivity;->prepared:Z\s+if-eqz v1, (:\w+)\s+invoke-interface \{v0\}, Landroidx/media3/common/Player;->isPlaying\(\)Z\s+move-result v0\s+if-eqz v0, \1\s+iget-object v0, p0, Lmov/film/PlayerActivity;->player:Landroidx/media3/exoplayer/ExoPlayer;')
            body, count = guard.subn('', body)
            if count != 1: raise ValueError('Upstream buffering pause guard changed')
        return body
    text, count = re.subn(r'(?ms)^\.method protected (onStart|onStop)\(\)V\n.*?^\.end method', lifecycle, text)
    if count != 2: raise ValueError('Lifecycle methods missing')
    calls = 0
    def play_calls(match):
        nonlocal calls
        body = match.group(0)
        target = r'invoke-interface \{(\w+)\}, Landroidx/media3/common/Player;->play\(\)V'
        if not re.search(target, body): return body
        if ' static ' in body.splitlines()[0]: raise ValueError('Unexpected static playback call')
        body, n = re.subn(target, lambda m: 'invoke-direct {p0, ' + m.group(1) + '}, ' + TYPE + '->airPlay(Landroidx/media3/common/Player;)V', body)
        calls += n
        return body
    text = re.sub(r'(?ms)^\.method .*?^\.end method', play_calls, text)
    if calls != 3: raise ValueError('Upstream playback call count changed: ' + str(calls))
    text += '''
.method private airPlay(Landroidx/media3/common/Player;)V
    .locals 1
    iget-boolean v0, p0, Lmov/film/PlayerActivity;->airStopped:Z
    if-eqz v0, :air_play
    invoke-virtual {p0}, Landroid/app/Activity;->isInPictureInPictureMode()Z
    move-result v0
    if-eqz v0, :air_done
    :air_play
    invoke-interface {p1}, Landroidx/media3/common/Player;->play()V
    :air_done
    return-void
.end method
'''
    return text, hunks(normalized(original), normalized(text))


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('apk', type=Path)
    p.add_argument('--output', type=Path, required=True)
    p.add_argument('--runtime', type=Path, default=ROOT / 'updater/build/runtime')
    p.add_argument('--keystore', type=Path, required=True)
    p.add_argument('--alias', required=True)
    p.add_argument('--ks-pass-file', type=Path, required=True)
    args = p.parse_args()
    if args.apk.resolve() == args.output.resolve(): raise ValueError('Separate output required')
    build = Path.home() / 'Library/Android/sdk/build-tools/36.0.0'
    cp = str(args.runtime.resolve() / '*')
    def run(*a): subprocess.run([str(x) for x in a], check=True)
    with tempfile.TemporaryDirectory(prefix='film-player-') as d:
        work = Path(d)
        run('javac', '-cp', cp, '-d', work, ROOT / 'FilmDexClass.java')
        java = ['java', '-cp', str(work) + ':' + cp, 'FilmDexClass']
        with zipfile.ZipFile(args.apk) as source:
            names = [n for n in source.namelist() if re.fullmatch(r'classes\d*\.dex', n) and TYPE in dex_classes(source.read(n))]
            if len(names) != 1: raise ValueError('Missing or duplicate player class')
            name = names[0]; dex = work / 'original.dex'; dex.write_bytes(source.read(name))
            run(*java, 'decode', dex, work / 'smali')
            player = work / 'smali/mov/film/PlayerActivity.smali'
            text, delta = patch(player.read_text()); player.write_text(text)
            run('java', '-cp', cp, 'org.jf.smali.Main', 'assemble', work / 'smali', '-o', work / 'player.dex')
            run(*java, 'merge', dex, work / 'player.dex', work / 'merged.dex')
            recipe_name = 'assets/airupdate/recipe.zip'; recipe = None
            if recipe_name in source.namelist():
                buffer = io.BytesIO()
                with zipfile.ZipFile(io.BytesIO(source.read(recipe_name))) as old, zipfile.ZipFile(buffer, 'w', zipfile.ZIP_DEFLATED) as new:
                    config = json.loads(old.read('recipe.json'))
                    entry = next((c for c in config['code'] if c['path'] == 'mov/film/PlayerActivity.smali'), None)
                    if entry is None:
                        entry = {'path': 'mov/film/PlayerActivity.smali', 'hunks': []}; config['code'].append(entry)
                    entry['hunks'].extend(delta)
                    for item in old.infolist():
                        new.writestr(item, json.dumps(config, ensure_ascii=False).encode() if item.filename == 'recipe.json' else old.read(item))
                recipe = buffer.getvalue()
            with zipfile.ZipFile(work / 'unsigned.apk', 'w', zipfile.ZIP_DEFLATED) as out:
                for item in source.infolist():
                    if signature_entry(item.filename): continue
                    data = (work / 'merged.dex').read_bytes() if item.filename == name else recipe if item.filename == recipe_name and recipe is not None else source.read(item)
                    out.writestr(item, data)
        run(build / 'zipalign', '-P', '16', '-f', '4', work / 'unsigned.apk', work / 'aligned.apk')
        args.output.parent.mkdir(parents=True, exist_ok=True)
        run(build / 'apksigner', 'sign', '--ks', args.keystore, '--ks-key-alias', args.alias, '--ks-pass', 'file:' + str(args.ks_pass_file), '--out', args.output, work / 'aligned.apk')
        run(build / 'apksigner', 'verify', args.output)
        print('Buffering pause, late autoplay guard, PiP and update recipe: patched')


if __name__ == '__main__': main()
