#!/bin/bash
# RTQ NOW emulator verification of the published release APK. Runs inside android-emulator-runner.
APK=apk/app-github-universal-debug.apk
P=app.rtqnow.debug
mkdir -p shots dumps
adb install -r "$APK" || { echo INSTALL-FAILED; exit 1; }
adb shell pm list packages | grep -q "$P" || { echo INSTALL-FAILED; exit 1; }
adb shell dumpsys package "$P" | grep -E "versionCode|versionName" | head -3
adb shell pm grant "$P" android.permission.READ_MEDIA_AUDIO || true
adb shell pm grant "$P" android.permission.POST_NOTIFICATIONS || true
for f in samples/*.wav; do
  b=$(basename "$f")
  adb push "$f" "/sdcard/Music/$b"
  adb shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d "file:///sdcard/Music/$b" >/dev/null
done
sleep 6
dump() {
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  adb shell cat /sdcard/ui.xml > "dumps/$1.xml" 2>/dev/null
}
shot() { adb exec-out screencap -p > "shots/$1.png"; dump "$1"; echo "SHOT $1"; }
ui_tap() {
  dump tap_tmp
  coords=$(python3 - "$1" <<'PY'
import sys, re
want = sys.argv[1]
x = open('dumps/tap_tmp.xml', encoding='utf-8', errors='ignore').read()
for m in re.finditer(r'<node[^>]*>', x):
    tag = m.group(0)
    t = re.search(r'text="([^"]*)"', tag)
    d = re.search(r'content-desc="([^"]*)"', tag)
    b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', tag)
    if b and ((t and t.group(1) == want) or (d and d.group(1) == want)):
        x1, y1, x2, y2 = map(int, b.groups())
        print(f"{(x1+x2)//2} {(y1+y2)//2}")
        break
PY
)
  if [ -n "$coords" ]; then adb shell input tap $coords; echo "TAPPED $1 at $coords"; else echo "NOT-FOUND $1"; return 1; fi
}
adb shell monkey -p "$P" -c android.intent.category.LAUNCHER 1
sleep 20
shot 01_launch
adb shell input keyevent KEYCODE_BACK; sleep 1
shot 01b_after_back
adb shell monkey -p "$P" -c android.intent.category.LAUNCHER 1; sleep 6
shot 02_home
for tab in Music Library Subscriptions Shorts Settings Profile; do
  ui_tap "$tab" && { sleep 4; shot "03_tab_$tab"; }
done
ui_tap Settings || true; sleep 3
ui_tap About || true; sleep 4
shot 04_about
adb shell input swipe 600 1800 600 600; sleep 2
shot 04b_about_scrolled
adb logcat -d | grep -E "FATAL EXCEPTION|AndroidRuntime" > dumps/crash.txt
adb logcat -d -t 400 > dumps/logcat_tail.txt
if [ -s dumps/crash.txt ]; then echo CRASH-DETECTED; exit 1; fi
echo SMOKE-DONE
