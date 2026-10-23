#!/usr/bin/env python3
"""Prove that the Chinese edition differs from upstream only at reviewed display literals."""
import argparse
import json
from pathlib import Path
import re
import subprocess

ROOT = Path(__file__).resolve().parents[1]
MANIFEST = ROOT / 'docs/localization-literals.json'


def git(*args):
    return subprocess.check_output(['git', *args], cwd=ROOT)


def expression_end(source, start):
    depth, i = 1, start
    while i < len(source):
        if source.startswith('//', i):
            end = source.find('\n', i)
            i = len(source) if end < 0 else end + 1
        elif source.startswith('/*', i):
            end = source.find('*/', i + 2)
            assert end >= 0
            i = end + 2
        elif source[i] == '"':
            i = string_end(source, i)
        elif source[i] == "'":
            i += 1
            while i < len(source):
                if source[i] == '\\':
                    i += 2
                elif source[i] == "'":
                    i += 1
                    break
                else:
                    i += 1
        else:
            if source[i] == '{':
                depth += 1
            elif source[i] == '}':
                depth -= 1
                if depth == 0:
                    return i
            i += 1
    raise AssertionError('Unclosed Kotlin template expression')


def string_end(source, start):
    assert source[start] == '"'
    assert not source.startswith('"""', start), 'Raw strings require a separate review'
    i = start + 1
    while i < len(source):
        if source[i] == '\\':
            i += 2
        elif source.startswith('${', i):
            i = expression_end(source, i + 2) + 1
        elif source[i] == '"':
            return i + 1
        else:
            i += 1
    raise AssertionError('Unclosed Kotlin string')


def literals(source):
    result, i = [], 0
    while i < len(source):
        if source.startswith('//', i):
            end = source.find('\n', i)
            i = len(source) if end < 0 else end + 1
        elif source.startswith('/*', i):
            end = source.find('*/', i + 2)
            assert end >= 0
            i = end + 2
        elif source[i] == '"':
            end = string_end(source, i)
            result.append((i, end, source[i:end]))
            i = end
        else:
            i += 1
    return result


def template_signature(literal):
    """Keep every interpolation expression; ignore only nested string display text."""
    result, i = [], 1
    while i < len(literal) - 1:
        if literal[i] == '\\':
            i += 2
        elif literal.startswith('${', i):
            end = expression_end(literal, i + 2)
            expression = literal[i + 2:end]
            parts, last = [], 0
            for start, stop, nested in literals(expression):
                parts.extend([expression[last:start], ('string', template_signature(nested))])
                last = stop
            parts.append(expression[last:])
            result.append(('expression', parts))
            i = end + 1
        elif literal[i] == '$':
            match = re.match(r'\$([A-Za-z_][A-Za-z_0-9]*)', literal[i:])
            assert match, 'New or invalid Kotlin interpolation'
            result.append(('reference', match.group(1)))
            i += len(match.group(0))
        else:
            i += 1
    return result


def restore_display_literals(current, original, entries):
    old, new = literals(original), literals(current)
    assert len(old) == len(new), 'String count changed'
    allowed = {entry['literal_index']: entry for entry in entries}
    assert len(allowed) == len(entries), 'Duplicate review entries'
    restored, last = [], 0
    for index, ((_, _, before), (start, stop, after)) in enumerate(zip(old, new)):
        if index in allowed:
            entry = allowed[index]
            assert before == entry['original'], f'Baseline mismatch at literal {index}'
            assert after == entry['translated'], f'Unreviewed translation at literal {index}'
            assert before != after
            assert template_signature(before) == template_signature(after), f'Interpolation code changed at literal {index}'
        else:
            assert before == after, f'Unreviewed literal {index}'
        restored.extend([current[last:start], before])
        last = stop
    restored.append(current[last:])
    assert ''.join(restored) == original, 'Non-display source changed (conditions, callbacks, values, imports or structure)'


def verify():
    manifest = json.loads(MANIFEST.read_text())
    base = manifest['base_commit']
    reviewed = manifest['files']
    count = 0
    for path, entries in reviewed.items():
        original = git('show', f'{base}:{path}').decode()
        current = (ROOT / path).read_text()
        restore_display_literals(current, original, entries)
        count += len(entries)
    paths = git('ls-tree', '-r', '--name-only', base).decode().splitlines()
    protected = 0
    for path in paths:
        is_production = any(path.startswith(module + '/') for module in
                            ('app', 'voice-api', 'sip', 'sip-pjsua2', 'gateway', 'telephony', 'privileged'))
        is_build = path in ('build.gradle.kts', 'settings.gradle.kts', 'gradle.properties', 'gradlew', 'gradlew.bat') or path.startswith('gradle/')
        if (is_production or is_build) and path not in reviewed:
            assert (ROOT / path).is_file(), f'Protected file removed: {path}'
            assert (ROOT / path).read_bytes() == git('show', f'{base}:{path}'), f'Protected file changed: {path}'
            protected += 1
    baseline = set(paths)
    for module in ('app', 'voice-api', 'sip', 'sip-pjsua2', 'gateway', 'telephony', 'privileged'):
        for path in git('ls-files', '--cached', '--others', '--exclude-standard', module).decode().splitlines():
            if (ROOT / path).is_file():
                assert path in baseline, f'Unexpected new module file: {path}'
    print(f'PASS: {count} reviewed display-literal replacements in {len(reviewed)} files; '
          f'all interpolation code and non-literal source bytes preserved; {protected} protected files identical to upstream.')


def self_test():
    original = 'Button(onClick = { save(true) }, enabled = error == null) { Text("Count: ${n + 1}") }'
    translated = original.replace('Count: ', '计数：')
    entries = [{'literal_index': 0, 'original': '"Count: ${n + 1}"', 'translated': '"计数：${n + 1}"'}]
    restore_display_literals(translated, original, entries)
    for tampered, review in [
        (translated.replace('save(true)', 'save(false)'), entries),
        (translated.replace('error == null', 'error != null'), entries),
        (translated.replace('n + 1', 'n + 2'), [dict(entries[0], translated='"计数：${n + 2}"')]),
        (translated.replace('计数：', '未审查：'), entries),
    ]:
        try:
            restore_display_literals(tampered, original, review)
        except AssertionError:
            continue
        raise AssertionError('Mutation guard failed to reject a changed callback, condition, expression or text')
    print('PASS: mutation checks reject changed callbacks, validation conditions, template expressions and unreviewed text.')


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--self-test', action='store_true')
    args = parser.parse_args()
    if args.self_test:
        self_test()
    verify()
