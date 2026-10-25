#!/usr/bin/env python3
"""Collect dependency copyright and license texts into the distributable."""
import json, subprocess
from pathlib import Path
root=Path(__file__).resolve().parents[1]
metadata=json.loads(subprocess.check_output(['cargo','metadata','--locked','--format-version','1','--filter-platform','x86_64-pc-windows-gnu'],cwd=root))
active={n['id'] for n in metadata['resolve']['nodes']}
parts=['Air Remote Companion — third-party notices\n']
for package in sorted(metadata['packages'],key=lambda p:p['name']):
    if package['id'] not in active or package['name']=='air-remote-companion':continue
    folder=Path(package['manifest_path']).parent
    files=set()
    for path in folder.iterdir():
        if path.name.lower().startswith(('license','licence','copying','notice')):
            if path.is_file():files.add(path)
            elif path.is_dir():files.update(p for p in path.rglob('*') if p.is_file())
    if package.get('license_file'):files.add(folder/package['license_file'])
    parts.append(f"\n{'='*72}\n{package['name']} {package['version']}\n{package.get('repository') or ''}\nLicense: {package.get('license') or ''}\n")
    for path in sorted(files):parts.append(f'\n--- {path.relative_to(folder)} ---\n'+path.read_text(errors='replace'))
(root/'target/bundle/THIRD-PARTY-NOTICES.txt').write_text('\n'.join(parts))
