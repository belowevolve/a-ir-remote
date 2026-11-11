#!/usr/bin/env python3
"""Build a personal film.mov APK and its autonomous update recipe. Never installs."""
import argparse
import os
from pathlib import Path
import subprocess
import sys
import tempfile
from add_updater import JAVA, validate_film_key

ROOT = Path(__file__).resolve().parent
PROJECT = ROOT.parents[2]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('apk', type=Path, help='Official signed film.mov APK')
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--phone', action='store_true')
    parser.add_argument('--keystore', type=Path, required=True, help='Film-only key: embedded for local self-updates')
    parser.add_argument('--alias', required=True)
    parser.add_argument('--ks-pass-file', type=Path, required=True)
    args = parser.parse_args()
    source, output = args.apk.resolve(), args.output.resolve()
    if source == output:
        raise ValueError('Input and output must differ')
    validate_film_key(args.keystore, args.alias, args.ks_pass_file)
    signing = ['--keystore', str(args.keystore.resolve()), '--alias', args.alias,
               '--ks-pass-file', str(args.ks_pass_file.resolve())]
    def run(script, *params):
        subprocess.run([sys.executable, str(ROOT / script), *map(str, params), *signing], check=True)
    env = dict(os.environ, JAVA_HOME=str(JAVA.parent))
    subprocess.run([str(PROJECT / 'gradlew'), '-p', str(ROOT / 'updater'),
                    'compileJava', 'runtimeJars', '--console=plain'], env=env, check=True)
    with tempfile.TemporaryDirectory(prefix='film-personal-build-') as temporary:
        work = Path(temporary)
        run('patch_profile.py', source, '--output', work / 'profile.apk', *(['--phone-theme'] if args.phone else []))
        current = work / 'profile.apk'
        apktool = PROJECT / 'artifacts/film-updater-tools/apktool.jar'
        if not args.phone:
            run('patch_clean_artwork.py', current, '--output', work / 'clean.apk', '--apktool', apktool)
            current = work / 'clean.apk'
        run('patch_badges.py', current, '--output', work / 'badges.apk', '--apktool', apktool)
        current = work / 'badges.apk'
        if not args.phone:
            for script in ['patch_player_lifecycle.py', 'patch_media_session.py']:
                patched = work / (Path(script).stem + '.apk')
                run(script, current, '--output', patched)
                current = patched
        run('add_updater.py', source, current, '--output', output)


if __name__ == '__main__':
    main()
