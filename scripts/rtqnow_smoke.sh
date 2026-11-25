#!/bin/bash
# RTQ NOW emulator check of the published release APK (text-driven, bounded). Runs inside android-emulator-runner.
APK=apk/app-github-universal-debug.apk
P=app.rtqnow.debug
mkdir -p shots dumps
R=dumps/results.txt; : > $R
log() { echo "$*" | tee -a $R; }
adb install -r "$APK" || { log "INSTALL-FAILED"; exit 1; }
log "INSTALLED $(adb shell dumpsys package $P | grep -E 'versionCode|versionName' | tr -s ' ' | tr '\n' ' ')"
adb shell pm grant $P android.permission.READ_MEDIA_AUDIO || true
adb shell pm grant $P android.permission.POST_NOTIFICATIONS || true
for f in samples/*.wav; do
  b=$(basename "$f"); adb push "$f" "/sdcard/Music/$b" >/dev/null
  adb shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d "file:///sdcard/Music/$b" >/dev/null
done
ui() { adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb shell cat /sdcard/ui.xml > dumps/cur.xml 2>/dev/null; }
shot() { ui; cp dumps/cur.xml "dumps/$1.xml"; adb exec-out screencap -p > "shots/$1.png"; log "SHOT $1"; }
# wait_for REGEX SECONDS -> 0 if a node with that label appears
wait_for() {
  local end=$((SECONDS + $2))
  while [ $SECONDS -lt $end ]; do ui; python3 scripts/rtqnow_ui.py has "$1" && return 0; sleep 2; done
  return 1
}
# tap_label REGEX -> 0 if found and tapped (uses current dump)
tap_label() {
  local c; c=$(python3 scripts/rtqnow_ui.py find "$1") || return 1
  adb shell input tap $c; log "TAPPED /$1/ at $c"; return 0
}
adb shell monkey -p $P -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
if wait_for '^Get started$' 45; then log "FOUND welcome screen"; shot 01_welcome; tap_label '^Get started$'; sleep 3
else log "UNMET welcome screen not found in 45s"; shot 01_unmet; fi
# onboarding: while a "Step N of 5" marker exists, tap Skip (else Continue). Max 8 pages.
n=0
while [ $n -lt 8 ]; do
  ui
  python3 scripts/rtqnow_ui.py has '^Step [0-9]+ of' || break
  n=$((n+1)); shot "02_onboard_$n"
  if tap_label '^Skip$'; then :; elif tap_label '^Continue'; then :; else log "UNMET no Skip/Continue on onboarding page $n"; break; fi
  sleep 3
done
if python3 scripts/rtqnow_ui.py has '^Step [0-9]+ of'; then log "UNMET onboarding not finished after $n pages"; STOP=1; else log "ONBOARDING-DONE after $n pages"; fi
if [ -z "$STOP" ]; then
  sleep 10; shot 03_home
  python3 scripts/rtqnow_ui.py navrow | tee -a $R
  python3 scripts/rtqnow_ui.py texts | head -60 > dumps/home_texts.txt
  i=0
  python3 scripts/rtqnow_ui.py navrow > dumps/nav.txt
  while read cx cy lab; do
    [ $i -ge 5 ] && break
    adb shell input tap $cx $cy; log "TAPPED nav $lab at $cx $cy"; sleep 5; shot "04_nav_${i}_${lab}"
    python3 scripts/rtqnow_ui.py texts | head -60 > "dumps/nav_${i}_texts.txt"
    i=$((i+1))
  done < dumps/nav.txt
fi
# Settings -> About: look for a Settings entry on any screen; scroll up to 5 times for About
if [ -z "$STOP" ]; then
  adb shell input keyevent KEYCODE_BACK; sleep 2
  ok=0
  for attempt in 1 2 3 4 5 6; do
    ui
    if python3 scripts/rtqnow_ui.py has '^(Settings|Open settings)$'; then tap_label '^(Settings|Open settings)$'; ok=1; sleep 4; break; fi
    j=0; while read cx cy lab; do
      [ $j -eq $((attempt-1)) ] && { adb shell input tap $cx $cy; sleep 3; }
      j=$((j+1))
    done < dumps/nav.txt
  done
  if [ $ok -eq 1 ]; then
    shot 05_settings
    found=0
    for s in 1 2 3 4 5 6; do
      ui
      if python3 scripts/rtqnow_ui.py has '^About'; then tap_label '^About'; found=1; sleep 4; break; fi
      adb shell input swipe 160 500 160 150; sleep 2
    done
    if [ $found -eq 1 ]; then shot 06_about; adb shell input swipe 160 500 160 150; sleep 2; shot 06b_about_scrolled
      python3 scripts/rtqnow_ui.py texts > dumps/about_texts.txt
      for w in RTQ_67 'Developer' 'Reddit' 'Donat' 'Support'; do
        if grep -q "$w" dumps/about_texts.txt; then log "ABOUT-TEXT contains $w"; else log "ABOUT-TEXT no $w"; fi
      done
    else log "UNMET About entry not found in Settings"; fi
  else log "UNMET Settings entry not found"; fi
fi
# Local fixture playback
if [ -z "$STOP" ]; then
  adb shell input keyevent KEYCODE_BACK; sleep 1; adb shell input keyevent KEYCODE_BACK; sleep 2
  played=0
  for path in 'Library' 'Local media|Local library|On device' 'Music library|Songs' 'tone_alpha'; do
    ui
    if python3 scripts/rtqnow_ui.py has "$path"; then tap_label "$path"; sleep 4; else log "UNMET local step /$path/ not visible"; fi
  done
  shot 07_local_after_steps
  ui
  if python3 scripts/rtqnow_ui.py has 'tone_'; then
    s1=$(adb shell dumpsys media_session | grep -m1 -o 'state=PlaybackState {state=[0-9]*, position=[0-9]*')
    sleep 5
    s2=$(adb shell dumpsys media_session | grep -m1 -o 'state=PlaybackState {state=[0-9]*, position=[0-9]*')
    log "MEDIA-SESSION t0: $s1"; log "MEDIA-SESSION t+5s: $s2"
    shot 08_playback
  else log "UNMET fixture track not visible; no playback claim"; fi
fi
# Spotify: login prompt only
if [ -z "$STOP" ]; then
  adb shell input keyevent KEYCODE_BACK; sleep 2; ui
  if python3 scripts/rtqnow_ui.py has 'Spotify'; then tap_label 'Spotify'; sleep 5; shot 09_spotify; else log "UNMET Spotify entry not visible"; fi
fi
adb logcat -d | grep "FATAL EXCEPTION" > dumps/crash.txt
adb logcat -d -t 400 > dumps/logcat_tail.txt
if [ -s dumps/crash.txt ]; then log CRASH-DETECTED; exit 1; fi
log SMOKE-DONE
