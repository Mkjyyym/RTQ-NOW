#!/bin/bash
# RTQ NOW emulator verification of the published release APK. Runs inside android-emulator-runner.
APK=apk/app-github-universal-debug.apk
P=app.rtqnow.debug
mkdir -p shots dumps
adb install -r "$APK" || { echo INSTALL-FAILED; exit 1; }
adb shell dumpsys package "$P" | grep -E "versionCode|versionName" | head -3
adb shell pm grant "$P" android.permission.READ_MEDIA_AUDIO || true
adb shell pm grant "$P" android.permission.POST_NOTIFICATIONS || true
adb shell wm size
shot() { adb exec-out screencap -p > "shots/$1.png"; adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb shell cat /sdcard/ui.xml > "dumps/$1.xml" 2>/dev/null; echo "SHOT $1"; }
# prints "cx cy" of clickable nodes: mode last = bottom-most/right-most, mode nav = bottom row sorted by x
pick() {
python3 - "$1" "$2" <<'PY'
import sys, re
mode, idx = sys.argv[1], int(sys.argv[2])
x = open('dumps/cur.xml', encoding='utf-8', errors='ignore').read()
nodes = []
for m in re.finditer(r'<node[^>]*>', x):
    tag = m.group(0)
    b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', tag)
    if b and 'clickable="true"' in tag:
        x1, y1, x2, y2 = map(int, b.groups())
        nodes.append(((x1+x2)//2, (y1+y2)//2, x2-x1, y2-y1))
H = max([n[1] for n in nodes] + [1])
if mode == 'last':
    if nodes:
        n = sorted(nodes, key=lambda n: (n[1], n[0]))[-1]
        print(n[0], n[1])
else:
    row = sorted([n for n in nodes if n[1] > H * 0.9], key=lambda n: n[0])
    if len(row) >= 3 and idx < len(row):
        print(row[idx][0], row[idx][1])
PY
}
adb shell monkey -p "$P" -c android.intent.category.LAUNCHER 1
sleep 25
shot 01_launch
for i in 1 2 3 4 5 6 7 8; do
  adb shell cat /sdcard/ui.xml > dumps/cur.xml
  c=$(pick nav 0)
  if [ -n "$c" ]; then echo "NAV-REACHED at step $i"; break; fi
  c=$(pick last 0)
  [ -z "$c" ] && { echo "NO-CLICKABLE"; break; }
  adb shell input tap $c; echo "TAP primary $c"; sleep 4
  shot "02_step_$i"
done
adb shell cat /sdcard/ui.xml > dumps/cur.xml
for i in 0 1 2 3 4; do
  c=$(pick nav $i)
  [ -z "$c" ] && break
  adb shell input tap $c; echo "TAP nav $i $c"; sleep 5
  shot "03_nav_$i"
  adb shell cat /sdcard/ui.xml > dumps/cur.xml
done
adb logcat -d | grep "FATAL EXCEPTION" > dumps/crash.txt
adb logcat -d -t 400 > dumps/logcat_tail.txt
if [ -s dumps/crash.txt ]; then echo CRASH-DETECTED; exit 1; fi
echo SMOKE-DONE
