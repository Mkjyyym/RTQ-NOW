#!/usr/bin/env python3
"""RTQ NOW build step: rebrand user-visible strings.

Replaces the standalone word "Flow" in string text with "NOW" in every
res/values*/strings.xml, and sets the developer entries in the default file.
Resource names, package names and the Flow-based credit/licence notices are
not touched (only text between > and < is rewritten).
Idempotent: running it twice changes nothing.
"""
import glob, re, sys

PAT = re.compile(r'(>[^<]*?)(?<![A-Za-z])Flow(?![A-Za-z])')
FIXED = [
    ('<string name="about_creator_name">A-EDev</string>', '<string name="about_creator_name">RTQ_67</string>'),
    ('<string name="settings_creator_role">Creator and developer of Flow</string>',
     '<string name="settings_creator_role">Developer of RTQ NOW</string>'),
]

def rebrand(s):
    while True:
        t = PAT.sub(lambda m: m.group(1) + 'NOW', s)
        if t == s:
            return s
        s = t

def main(root='app/src/main/res'):
    n = 0
    for p in glob.glob(root + '/values*/strings.xml'):
        s = open(p, encoding='utf-8').read()
        t = rebrand(s)
        if p.endswith('/values/strings.xml'):
            for a, b in FIXED:
                t = t.replace(a, b)
        if t != s:
            open(p, 'w', encoding='utf-8', newline='').write(t)
            n += 1
    print('rebranded files:', n)

if __name__ == '__main__':
    main(*sys.argv[1:])
