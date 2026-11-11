"""Relocate the updater dependencies so film.mov's bundled Guava cannot shadow them.

Rewrite JVM CONSTANT_Utf8 entries (including reflection names) before D8. No
method bytecode, offsets or non-UTF8 constants are changed. All other constant
pool tags retain their original byte representation.
"""
import struct
import zipfile

PREFIXES = ('com/google/', 'com/android/apksig/', 'com/reandroid/', 'org/jf/',
            'org/antlr/', 'org/stringtemplate/', 'antlr/', 'com/beust/',
            'org/checkerframework/', 'com/googlecode/', 'org/codehaus/')
DEST = 'mov/film/airupdate/vendor/'


def relocate(value):
    for prefix in PREFIXES:
        value = value.replace(prefix.encode(), (DEST + prefix).encode())
        value = value.replace(prefix.replace('/', '.').encode(), (DEST + prefix).replace('/', '.').encode())
    return value


def class_bytes(data):
    if data[:4] != b'\xca\xfe\xba\xbe':
        raise ValueError('Invalid JVM class')
    count = struct.unpack_from('>H', data, 8)[0]
    out = bytearray(data[:10]); offset = 10; index = 1
    sizes = {3: 4, 4: 4, 5: 8, 6: 8, 7: 2, 8: 2, 9: 4, 10: 4, 11: 4,
             12: 4, 15: 3, 16: 2, 17: 4, 18: 4, 19: 2, 20: 2}
    while index < count:
        tag = data[offset]; out.append(tag); offset += 1
        if tag == 1:
            length = struct.unpack_from('>H', data, offset)[0]; offset += 2
            value = relocate(data[offset:offset + length]); offset += length
            out += struct.pack('>H', len(value)) + value
        else:
            size = sizes[tag]; out += data[offset:offset + size]; offset += size
            if tag in (5, 6): index += 1
        index += 1
    out += data[offset:]
    return out


def runtime_jar(classes, jars, output):
    with zipfile.ZipFile(output, 'w', compression=zipfile.ZIP_DEFLATED) as out:
        names = set()
        def add(name, data):
            if not name.endswith('.class') or name.endswith('module-info.class'):
                return
            name = relocate(name.encode()).decode()
            if name in names:
                raise ValueError('Duplicate runtime class: ' + name)
            names.add(name)
            out.writestr(zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0)), class_bytes(data), compress_type=zipfile.ZIP_DEFLATED)
        for file in classes.rglob('*.class'):
            add(file.relative_to(classes).as_posix(), file.read_bytes())
        for jar in jars:
            with zipfile.ZipFile(jar) as src:
                for name in src.namelist():
                    add(name, src.read(name))
