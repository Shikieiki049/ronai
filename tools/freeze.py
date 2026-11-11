#!/usr/bin/env python3
"""
Freeze the decision code of a tagged build as a second "brain" that the self-play harness can
play against the current code in the same game.

    py tools/freeze.py build204            -> src/baseline/java/com/ronstrat/base/   (spec: expert@base)
    py tools/freeze.py build210 --name b210                                          (spec: expert@b210)
    py tools/freeze.py --worktree          -> freeze the working tree instead of a tag (for trying it out)

Every class of package com.ronstrat except the harness/infrastructure ones is copied into package
com.ronstrat.<name>. The copies see each other first (same package) and fall back to the current
infrastructure classes through `import com.ronstrat.*`. The baseline source set is compiled by
`build.ps1 compileJava compileBaselineJava` and is never part of the released jar.
"""
import argparse, pathlib, re, shutil, subprocess, sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
SRC = 'src/main/java/com/ronstrat'
# shared by every brain: the harness, the lobby, networking, and the types of the Agent interface
INFRA = {'Agent', 'Arena', 'AutoTest', 'BotManager', 'Difficulty', 'LobbyAi', 'LobbySettings', 'Match',
         'MatchSpec', 'Net', 'RonStrategist', 'StartFinder', 'StratCommands', 'WaterRescue', 'Telemetry'}


def git(*args):
    return subprocess.run(['git', *args], cwd=ROOT, check=True, capture_output=True).stdout


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('tag', nargs='?')
    ap.add_argument('--name', default='base')
    ap.add_argument('--worktree', action='store_true')
    a = ap.parse_args()
    if not a.worktree and not a.tag:
        ap.error('give a tag (or --worktree)')
    if not re.fullmatch(r'[a-z][a-z0-9]*', a.name):
        ap.error('--name: lower-case letters and digits only (it becomes a package name)')

    if a.worktree:
        files = {p.name: p.read_bytes() for p in (ROOT / SRC).glob('*.java')}
        origin = 'working tree'
    else:
        names = git('ls-tree', '--name-only', a.tag, SRC + '/').decode().split()
        files = {pathlib.PurePosixPath(n).name: git('show', f'{a.tag}:{n}') for n in names if n.endswith('.java')}
        origin = a.tag
    if 'Agent.java' not in files:
        sys.exit(f'{origin} has no Agent.java: builds before build204 cannot be frozen')

    out = ROOT / 'src/baseline/java/com/ronstrat' / a.name
    if out.exists():
        shutil.rmtree(out)
    out.mkdir(parents=True)
    n = 0
    for fname, raw in sorted(files.items()):
        cls = fname[:-5]
        if cls in INFRA:
            continue
        s = raw.decode('utf-8')
        s, k = re.subn(r'^package com\.ronstrat;', f'package com.ronstrat.{a.name};\n\nimport com.ronstrat.*;', s, count=1, flags=re.M)
        if k != 1:
            sys.exit(f'{fname}: no package line')
        if cls == 'Bot':
            s, k = re.subn(r'static final String BRAIN = "";', f'static final String BRAIN = "{a.name}";', s)
            if k != 1:
                sys.exit('Bot.java: BRAIN constant not found')
        if cls == 'BotParams':
            # the tuner's overrides (-Dronstrat.params.<tier>) are for the code under test, not for the baseline
            s = s.replace('"ronstrat.params."', f'"ronstrat.{a.name}params."')
            s = s.replace('".defaults.json"', f'".{a.name}.defaults.json"')
        (out / fname).write_text(s, encoding='utf-8', newline='')
        n += 1
    (out / 'FROZEN.txt').write_text(f'{origin}\n', encoding='utf-8')
    print(f'froze {n} classes of {origin} into {out.relative_to(ROOT)} (brain "{a.name}")')


if __name__ == '__main__':
    main()
