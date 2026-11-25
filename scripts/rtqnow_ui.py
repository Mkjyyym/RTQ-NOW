#!/usr/bin/env python3
"""UI-dump helper for the emulator check. Usage: rtqnow_ui.py find REGEX | has REGEX | navrow | texts"""
import re, sys

def nodes(path='dumps/cur.xml'):
    x = open(path, encoding='utf-8', errors='ignore').read()
    out = []
    for m in re.finditer(r'<node[^>]*>', x):
        tag = m.group(0)
        b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', tag)
        if not b:
            continue
        t = re.search(r'\btext="([^"]*)"', tag)
        d = re.search(r'content-desc="([^"]*)"', tag)
        x1, y1, x2, y2 = map(int, b.groups())
        out.append({'t': t.group(1) if t else '', 'd': d.group(1) if d else '',
                    'cx': (x1 + x2) // 2, 'cy': (y1 + y2) // 2, 'x1': x1, 'y1': y1, 'x2': x2, 'y2': y2,
                    'click': 'clickable="true"' in tag})
    return out

def main():
    cmd = sys.argv[1]
    ns = nodes()
    if cmd in ('find', 'has'):
        rx = re.compile(sys.argv[2])
        for n in ns:
            lab = n['t'] or n['d']
            if lab and rx.search(lab) and n['x2'] > n['x1'] and n['y2'] > n['y1']:
                if cmd == 'find':
                    print(n['cx'], n['cy'])
                sys.exit(0)
        sys.exit(1)
    elif cmd == 'navrow':
        H = max([n['y2'] for n in ns] + [1])
        row = sorted([n for n in ns if n['click'] and n['cy'] > H * 0.9 and (n['x2'] - n['x1']) < 400], key=lambda n: n['cx'])
        for n in row:
            print(n['cx'], n['cy'], (n['t'] or n['d'] or '?').replace(' ', '_'))
    elif cmd == 'texts':
        for n in ns:
            lab = n['t'] or n['d']
            if lab:
                print(lab)

main()
