#!/usr/bin/env python3
"""Reproducible personal film.mov Material patch; never installs/uninstalls anything."""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import urllib.request
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent
PROJECT = ROOT.parents[2]
APKTOOL_VERSION = '2.12.1'
APKTOOL_SHA = '66cf4524a4a45a7f56567d08b2c9b6ec237bcdd78cee69fd4a59c8a0243aeafa'
UPSTREAM_CERT = '9f37b040ce8ddb4ea47b5c4ec8b0fd452cc89f74082636e09cc5a921737ff1b0'
ANDROID = '{http://schemas.android.com/apk/res/android}'
DEFAULT = dict(accent=0xff426b2b, accent_hot=0xffb8f397, accent_soft=0x66426b2b,
               back=0xff101411, surface=0xff181d18, text=0xffe1e9da, muted=0xffc1c9b7)


class Incompatible(RuntimeError):
    pass


def run(args, env=None):
    result = subprocess.run([str(x) for x in args], text=True, capture_output=True, env=env)
    if result.returncode:
        raise RuntimeError('Command failed: ' + str(args[0]) + '\n' + result.stdout[-4000:] + result.stderr[-4000:])
    return result.stdout


def method(text, signature):
    pattern = re.compile(r'^\.method [^\n]*' + re.escape(signature) + r'\n.*?^\.end method', re.M | re.S)
    matches = list(pattern.finditer(text))
    if len(matches) != 1:
        raise Incompatible('Expected one method: ' + signature)
    return matches[0]


def _attach_metadata(tree):
    classes = list(tree.glob('smali*/mov/film/TvRows$Api26.smali'))
    if len(classes) != 1:
        raise Incompatible('TvRows$Api26 class moved or missing')
    target = classes[0]
    text = target.read_text()
    if 'airHomeMetadata' in text:
        raise Incompatible('APK is already patched')
    match = method(text, 'values(Landroid/content/Context;JLmov/film/TvRows$Tile;ILjava/util/Set;)Landroid/content/ContentValues;')
    body = match.group()
    # Preserve Tile in a new local: the original method reuses wide parameter registers later.
    if not re.search(r'\.locals 2\b', body) or len(re.findall(r'^    return-object v0$', body, re.M)) != 2:
        raise Incompatible('Preview-program register/return layout changed')
    if re.search(r'\bv(?:[2-9]|[1-9]\d+)\b', body):
        raise Incompatible('Unexpected physical parameter aliases in method')
    start = '    new-instance v0, Landroid/content/ContentValues;'
    if body.count(start) != 1:
        raise Incompatible('ContentValues initialization changed')
    art = target.with_name('TvArt.smali').read_text()
    if '->getLastPathSegment()Ljava/lang/String;' not in method(art, 'openFile(Landroid/net/Uri;Ljava/lang/String;)Landroid/os/ParcelFileDescriptor;').group():
        raise Incompatible('TvArt URI handling changed')
    tile = target.with_name('TvRows$Tile.smali').read_text()
    for field in ['poster', 'mark', 'seasonMark', 'news']:
        if not re.search(r'^\.field [^\n]*\b' + field + r':Ljava/lang/String;$', tile, re.M):
            raise Incompatible('Missing Tile field: ' + field)
    body = body.replace('.locals 2', '.locals 3', 1).replace(start, '    move-object v2, p3\n\n' + start, 1)
    call = '    invoke-static {v0, v2}, Lmov/film/TvRows$Api26;->airHomeMetadata(Landroid/content/ContentValues;Lmov/film/TvRows$Tile;)V\n'
    body = body.replace('    return-object v0', call + '    return-object v0')
    template = (ROOT / 'profile/metadata.smali').read_text()
    target.write_text(text[:match.start()] + body + text[match.end():] + '\n' + template)
    return 2


def attach_metadata(tree):
    import re,xml.etree.ElementTree as ET
    count=_attach_metadata(tree)
    values={el.get('name'):el.text.strip() for file in (tree/'res/values').glob('*.xml') for el in ET.parse(file).getroot() if el.tag=='color' and el.text}
    night=tree/'res/values-night/colors.xml'
    if night.exists():values.update({el.get('name'):el.text.strip() for el in ET.parse(night).getroot() if el.tag=='color' and el.text})
    def resolve(raw,seen=()):
        if not raw.startswith('@color/'):return raw
        name=raw[7:]
        if name in seen or name not in values:raise RuntimeError('Unresolved or cyclic color: '+raw)
        return resolve(values[name],seen+(name,))
    changes=0
    for file in (tree/'res').glob('drawable*/*.xml'):
        before=file.read_text()
        after=re.sub(r'@color/[a-zA-Z0-9_]+',lambda m:resolve(m[0]),before)
        for key,value in {'transparent':'#00000000','white':'#ffffffff','black':'#ff000000'}.items():after=after.replace('@android:color/'+key,value)
        if before!=after:file.write_text(after);changes+=1
    print('Resolved base color aliases in',changes,'drawables',flush=True)
    # 4.0 centralizes UI colors in mutable Palette fields; refresh those even on apply's early return.
    file=tree/'smali/mov/film/Palette.smali';text=file.read_text();match=method(text,'apply(Z)V')
    colors={'ACCENT':0xff8b5cf6,'ACCENT_HOT':0xffa855f7,'BACK':0xff0b0b10,'CARD':0xff14141c,'RAIL':0xff0f0f15,'TEXT':0xffe4e4e7,'TEXT_SOFT':0xffe4e4e7,'TEXT_DIM':0xffa1a1aa,'TEXT_MUTED':0xff8b8b96,'EDGE':0x558b5cf6,'EDGE_FADE':0x0d8b5cf6,'LIT':0xffe9d5ff,'SHADE':0xc00b0b10,'KNOB':0xffa1a1aa,'LINE':0x22a1a1aa}
    hook=''
    for field,color in colors.items():
        assert re.search(r'^\.field public static '+field+r':I',text,re.M),field
        signed=color if color<0x80000000 else color-0x100000000
        value=('-0x%x' % -signed) if signed<0 else ('0x%x' % signed)
        hook+='    const v0, '+value+' # AirHome semantic role\n    invoke-static {v0}, Lmov/film/DynamicTheme;->color(I)I\n    move-result v0\n    sput v0, Lmov/film/Palette;->'+field+':I\n'
    body=match.group();assert body.count('    return-void')==2
    file.write_text(text[:match.start()]+body.replace('    return-void',hook+'    return-void')+text[match.end():])
    return count


def palette_map(palette):
    high = 0xff000000
    for shift in (16, 8, 0):
        channel = round(((palette['surface'] >> shift) & 255) * .88
                        + ((palette['accent_hot'] >> shift) & 255) * .12)
        high |= channel << shift
    return {
        0xff8b5cf6: palette['accent'], 0xff7c3aed: palette['accent'],
        0xff5b2bb0: palette['accent'], 0xff7e3fb8: palette['accent'],
        0xffa855f7: palette['accent_hot'], 0xffe9d5ff: palette['accent_hot'],
        0xff0b0b10: palette['back'], 0xff171029: palette['surface'],
        0xff14141c: palette['surface'], 0xff0f0f15: palette['surface'],
        0xffa1a1aa: palette['muted'], 0xff8b8b96: palette['muted'],
        0xffe4e4e7: palette['text'], 0x668b5cf6: palette['accent_soft'],
        0xff15131f: palette['surface'], 0xff191922: palette['surface'],
        0xff1b1729: palette['surface'], 0xff1e1b2e: palette['surface'],
        0xff1e1e29: palette['surface'], 0xff221f33: high,
        0xff241c3d: high, 0xff2a2048: high, 0xff2a2340: high, 0xff3b2e5e: high,
    }


def palette_roles():
    # Resolve semantic roles from a fixed, distinct reference palette, not the user's RGB values.
    reference = list(DEFAULT.values())
    return {original: reference.index(target) if target in reference else None
            for original, target in palette_map(DEFAULT).items()}


def preserve_fresh_backup(tree):
    files = list(tree.glob('smali*/mov/film/Backup.smali'))
    if len(files) != 1:
        raise Incompatible('Backup class moved or missing')
    path = files[0]
    text = path.read_text()
    start = method(text, '<clinit>()V')
    body = start.group()
    pattern = r'(    const-string v0, "resume_at"\n)\s*filled-new-array \{v0\}, \[Ljava/lang/String;'
    if len(re.findall(pattern, body)) != 1:
        raise Incompatible('Backup META register layout changed')
    # Native import already preserves Boolean/Integer/Long/String values for META stores.
    apply = method(text, 'apply(Landroid/content/Context;Ljava/lang/String;)I').group()
    if '->putLong(Ljava/lang/String;J)' not in apply or '->putBoolean(Ljava/lang/String;Z)' not in apply:
        raise Incompatible('Typed backup import changed')
    body = re.sub(pattern, lambda m: m[1] + '\n    const-string v1, "fresh"\n\n    filled-new-array {v0, v1}, [Ljava/lang/String;', body)
    path.write_text(text[:start.start()] + body + text[start.end():])
    return True


def remove_launch_flash(tree):
    """Skip only the chosen-mode logo delay; keep first-run and explicit chooser paths."""
    files = list(tree.glob('smali*/mov/film/StartActivity.smali'))
    if len(files) != 1:
        raise Incompatible('Start activity moved or missing')
    path = files[0]
    text = path.read_text()
    match = method(text, 'onCreate(Landroid/os/Bundle;)V')
    body = match.group()
    guard = '    if-nez v0, :cond_1\n'
    if body.count(guard) != 1 or 'Lmov/film/Look;->chosen()Z' not in body:
        raise Incompatible('Startup chooser guards changed')
    begin = body.index(guard) + len(guard)
    end = body.index('    :cond_1', begin)
    splash = body[begin:end]
    callback = path.with_name('StartActivity$1.smali').read_text()
    if ('->splash()Landroid/view/View;' not in splash
            or 'const-wide/16 v1, 0x190' not in splash
            or '->postDelayed(Ljava/lang/Runnable;J)Z' not in splash
            or 'const/4 v1, 0x0' not in method(callback, 'run()V').group()
            or '->access$000(Lmov/film/StartActivity;Z)V' not in callback):
        raise Incompatible('Startup logo delay/callback changed')
    method(text, 'go(Z)V')
    direct = '\n    const/4 v1, 0x0\n\n    invoke-direct {p0, v1}, Lmov/film/StartActivity;->go(Z)V\n\n    return-void\n\n'
    body = body[:begin] + direct + body[end:]

    # Android 12+ also has a system launch icon. Only the routing Activity uses this
    # neutral starting window; catalogue/player themes and launcher icons stay intact.
    manifest = ET.parse(tree / 'AndroidManifest.xml')
    start = next((a for a in manifest.findall('application/activity')
                  if a.get(ANDROID + 'name') == 'mov.film.StartActivity'), None)
    if start is None or start.get(ANDROID + 'theme') not in (None, '@style/AppTheme.Launch'):
        raise Incompatible('Start activity theme changed')
    for qualifier in ['values', 'values-v31']:
        folder = tree / 'res' / qualifier
        folder.mkdir(exist_ok=True)
        resources = ET.Element('resources')
        style = ET.SubElement(resources, 'style', {'name': 'AirHome.Start', 'parent': '@style/AppTheme'})
        ET.SubElement(style, 'item', {'name': 'android:windowBackground'}).text = '@color/back'
        if qualifier.endswith('v31'):
            for name, value in {
                'windowSplashScreenBackground': '@color/back',
                'windowSplashScreenAnimatedIcon': '@android:color/transparent',
                'windowSplashScreenIconBackgroundColor': '@android:color/transparent',
            }.items():
                ET.SubElement(style, 'item', {'name': 'android:' + name}).text = value
        ET.ElementTree(resources).write(folder / 'film_start.xml', encoding='utf-8', xml_declaration=True)
    start.set(ANDROID + 'theme', '@style/AirHome.Start')
    manifest.write(tree / 'AndroidManifest.xml', encoding='utf-8', xml_declaration=True)
    path.write_text(text[:match.start()] + body + text[match.end():])
    return 400


def apply_material(tree, palette, resources_only=False):
    mapping = palette_map(palette)
    # Only known original palette colors, never video blacks, status red/green or artwork.
    def argb(value):
        alpha = value >> 24
        opaque = value | 0xff000000
        if value in mapping:
            return mapping[value]
        if opaque in mapping and alpha not in (0, 255):
            return (mapping[opaque] & 0xffffff) | (alpha << 24)
        return value
    constants = 0
    for path in ([] if resources_only else tree.glob('smali*/mov/film/**/*.smali')):
        def replace(match):
            nonlocal constants
            value = int(match[3], 16) & 0xffffffff
            updated = argb(value)
            if updated == value:
                return match[0]
            constants += 1
            signed = updated if updated < 0x80000000 else updated - 0x100000000
            return match[1] + 'const ' + match[2] + ', ' + ('-0x%x' % -signed if signed < 0 else '0x%x' % signed)
        text = path.read_text()
        path.write_text(re.sub(r'^([ \t]*)const(?:/high16)? (\w+), (-?0x[0-9a-f]+)[ \t]*$', replace, text, flags=re.M))
    if not resources_only and constants < 20:
        raise Incompatible('Native palette changed: too few matching color constants')
    resources = 0
    for path in (tree / 'res').rglob('*.xml'):
        text = path.read_text()
        def replace_color(match):
            nonlocal resources
            raw = match[0][1:]
            value = int(raw, 16) | (0xff000000 if len(raw) == 6 else 0)
            updated = argb(value)
            if updated != value:
                resources += 1
                return '#%08x' % updated
            return match[0]
        # Purple filled gradients become a consistent dark Material fill, not light ends with white text.
        def gradient(match):
            accents = {0x7c3aed, 0xa855f7, 0x8b5cf6, 0x5b2bb0, 0x7e3fb8}
            if any((int(color[1:], 16) & 0xffffff) in accents for color in re.findall(r'#[0-9a-fA-F]{8}', match[0])):
                return re.sub(r'(android:(?:startColor|endColor|centerColor)=")[^"]+',
                              lambda m: m[1] + '#%08x' % palette['accent'], match[0])
            return match[0]
        text = re.sub(r'<gradient\b[^>]*/>', gradient, text)
        path.write_text(re.sub(r'#[0-9a-fA-F]{8}\b|#[0-9a-fA-F]{6}\b', replace_color, text))
    if resources < 10:
        raise Incompatible('Resource palette changed: too few matching colors')
    return dict(native_color_constants=constants, resource_colors=resources)


def tools():
    sdk = Path(os.environ.get('ANDROID_HOME', Path.home() / 'Library/Android/sdk'))
    candidates = sorted((sdk / 'build-tools').glob('*'), key=lambda p: tuple(int(n) for n in p.name.split('.') if n.isdigit()))
    build = next((p for p in reversed(candidates) if (p / 'apksigner').exists()), None)
    if not build:
        raise RuntimeError('Set ANDROID_HOME to an Android SDK with build-tools')
    java_home = Path(os.environ.get('JAVA_HOME', '/Applications/Android Studio.app/Contents/jbr/Contents/Home'))
    java = java_home / 'bin/java'
    if not java.exists():
        raise RuntimeError('Set JAVA_HOME to JDK 17+')
    env = dict(os.environ, JAVA_HOME=str(java_home))
    return build, java, env


def apktool(java, env):
    target = PROJECT / 'artifacts/film-updater-tools/apktool.jar'
    if not target.exists() or hashlib.sha256(target.read_bytes()).hexdigest() != APKTOOL_SHA:
        target.parent.mkdir(parents=True, exist_ok=True)
        url = f'https://github.com/iBotPeaches/Apktool/releases/download/v{APKTOOL_VERSION}/apktool_{APKTOOL_VERSION}.jar'
        with urllib.request.urlopen(url, timeout=60) as response:
            data = response.read(40 * 1024 * 1024)
        if hashlib.sha256(data).hexdigest() != APKTOOL_SHA:
            raise RuntimeError('Apktool checksum mismatch')
        target.write_bytes(data)
    return [java, '-jar', target]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('apk', type=Path, help='Original signed film.mov APK')
    parser.add_argument('--output', type=Path, help='APK path; phone mode defaults to a separate phone/current.apk folder')
    colors = parser.add_mutually_exclusive_group()
    colors.add_argument('--palette', type=Path, help='JSON with accent/accent_hot/accent_soft/back/surface/text/muted integers')
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument('--phone-theme', action='store_true', help='Use Android Material You colors and the original logo as a themed adaptive icon')
    mode.add_argument('--static-theme', action='store_true', help='Keep the build-time palette instead of reading Air Home at runtime')
    mode.add_argument('--badges-only', action='store_true', help='Keep original film.mov interface colors; patch badge metadata only')
    colors.add_argument('--palette-from-tv', metavar='ADB_SERIAL', help='Read palette from running Air Home 0.18+; no navigation')
    parser.add_argument('--keystore', type=Path, default=Path.home() / '.android/debug.keystore')
    parser.add_argument('--alias', default='androiddebugkey')
    parser.add_argument('--ks-pass-file', type=Path, help='Password file for a custom keystore')
    args = parser.parse_args()
    build, java, env = tools()
    source = args.apk.resolve()
    default_output = PROJECT / ('artifacts/film-profile/phone/current.apk' if args.phone_theme else 'artifacts/film-profile/tv/current.apk')
    output = (args.output or default_output).resolve()
    if source == output:
        raise RuntimeError('Input and output must differ')
    # Remove stale results before any check: a rejected update must not look like a successful build.
    output.unlink(missing_ok=True)
    output.with_suffix('.json').unlink(missing_ok=True)
    verify = run([build / 'apksigner', 'verify', '--print-certs', source], env)
    if f'SHA-256 digest: {UPSTREAM_CERT}' not in verify:
        raise Incompatible('Unrecognized upstream signer (or APK already re-signed)')
    palette = dict(DEFAULT)
    if args.palette:
        data = json.loads(args.palette.read_text())
        if isinstance(data, dict) and isinstance(data.get('palette'), dict):
            data = data['palette']
        if not isinstance(data, dict) or not set(DEFAULT).issubset(data):
            raise ValueError('Palette JSON must contain all seven documented color roles')
        palette.update(data)
    if args.palette_from_tv:
        dump = run(['adb', '-s', args.palette_from_tv, 'shell', 'dumpsys', 'activity', 'belowevolve.tvhome/.HomeActivity'])
        match = re.search(r'AIRHOME_PALETTE (\{[^\n]+\})', dump)
        if not match:
            raise RuntimeError('Air Home 0.18+ palette unavailable; open Home once or pass --palette')
        palette.update(json.loads(match[1]))
    palette = {key: int(palette[key]) & 0xffffffff for key in DEFAULT}
    if any(palette[key] >> 24 != 255 for key in DEFAULT if key != 'accent_soft'):
        raise ValueError('Material color roles must be opaque ARGB values')
    if not args.keystore.is_file():
        raise RuntimeError('Signing key missing. Keep the same keystore for every patched update.')
    if args.keystore != Path.home() / '.android/debug.keystore' and not args.ks_pass_file:
        raise RuntimeError('Custom keystore requires --ks-pass-file')
    command = apktool(java, env)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.with_name('palette.json').write_text(json.dumps(palette, indent=2) + '\n')
    with tempfile.TemporaryDirectory(prefix='film-patch-') as temporary:
        work = Path(temporary)
        decoded = work / 'decoded'
        print('Decode / validate original APK...', flush=True)
        run(command + ['d', '-f', '-o', decoded, source], env)
        splash_delay = remove_launch_flash(decoded)
        manifest = ET.parse(decoded / 'AndroidManifest.xml')
        if manifest.getroot().get('package') != 'mov.film':
            raise Incompatible('Not film.mov')
        stats = dict(metadata_return_sites=attach_metadata(decoded))
        stats['startup_logo_delay_removed_ms'] = splash_delay
        stats['fresh_backup_preserved'] = preserve_fresh_backup(decoded)
        if not args.badges_only:
            if not args.static_theme:
                spec = importlib.util.spec_from_file_location('film_dynamic', ROOT / 'profile/dynamic.py')
                dynamic = importlib.util.module_from_spec(spec)
                spec.loader.exec_module(dynamic)
                stats.update(dynamic.inject(decoded, palette, palette_map(palette), palette_roles(), build, java, env, command, run, Incompatible, phone=args.phone_theme))
                if not args.phone_theme:
                    ET.SubElement(manifest.getroot(), 'uses-permission', {ANDROID + 'name': 'belowevolve.tvhome.permission.READ_THEME'})
                    queries = manifest.find('queries')
                    if queries is None: queries = ET.SubElement(manifest.getroot(), 'queries')
                    ET.SubElement(queries, 'provider', {ANDROID + 'authorities': 'belowevolve.tvhome.theme'})
            stats.update(apply_material(decoded, palette, resources_only=not args.static_theme))
        if args.phone_theme:
            spec = importlib.util.spec_from_file_location('film_phone', ROOT / 'profile/phone.py')
            phone = importlib.util.module_from_spec(spec)
            spec.loader.exec_module(phone)
            stats.update(phone.resources(decoded))
        application = manifest.find('application')
        if application is None:
            raise Incompatible('Application manifest missing')
        ET.SubElement(application, 'meta-data', {ANDROID + 'name': 'belowevolve.airhome.patch', ANDROID + 'value': '1'})
        ET.register_namespace('android', ANDROID[1:-1])
        manifest.write(decoded / 'AndroidManifest.xml', encoding='utf-8', xml_declaration=True)
        print('Build / align / sign / verify...', flush=True)
        unsigned, aligned = work / 'unsigned.apk', work / 'aligned.apk'
        run(command + ['b', '--aapt', build / 'aapt2', '-o', unsigned, decoded], env)
        run([build / 'zipalign', '-P', '16', '-f', '4', unsigned, aligned], env)
        signing = [build / 'apksigner', 'sign', '--ks', args.keystore, '--ks-key-alias', args.alias]
        signing += ['--ks-pass', 'file:' + str(args.ks_pass_file)] if args.ks_pass_file else ['--ks-pass', 'pass:android']
        signed = work / 'signed.apk'
        run(signing + ['--out', signed, aligned], env)
        signed_info = run([build / 'apksigner', 'verify', '--print-certs', signed], env)
        run([build / 'zipalign', '-c', '-P', '16', '4', signed], env)
        report = dict(schema=1, input_sha256=hashlib.sha256(source.read_bytes()).hexdigest(),
                      output_sha256=hashlib.sha256(signed.read_bytes()).hexdigest(), palette=palette,
                      apktool=APKTOOL_VERSION, checks=stats, signed_certificate=signed_info.strip())
        shutil.copy2(signed, output)
        output.with_suffix('.json').write_text(json.dumps(report, indent=2) + '\n')
    print('Ready:', output)
    print('No application installed or uninstalled. Original film.mov uses a different signing key.')


if __name__ == '__main__':
    try:
        main()
    except (RuntimeError, OSError, ValueError) as error:
        raise SystemExit(str(error))
