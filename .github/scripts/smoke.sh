#!/usr/bin/env bash
# Installs the APK on the emulator, walks through the main screens and saves screenshots.
set -x
OUT=${1:-shots}
mkdir -p "$OUT"
APK=$(ls apk/*.apk | head -1)
PKG=com.joe.novelreader
UI="python3 .github/scripts/ui.py"

shot() { sleep "${2:-3}"; adb exec-out screencap -p > "$OUT/$1.png"; }

adb install -r "$APK" || exit 1
adb shell pm grant $PKG android.permission.POST_NOTIFICATIONS || true
adb logcat -c
adb shell am start -n $PKG/.MainActivity
shot 01-launch 12
shot 02-discover 15
adb shell input swipe 540 1800 540 700 400
shot 03-discover-scrolled 6
$UI text Browse
shot 04-browse 4
$UI text NovelBin
shot 05-novelbin 12
$UI cover 400
shot 06-novel 12
$UI contains "Add to library"
sleep 2
$UI contains "Start reading"
shot 07-reader 12
adb shell input tap 540 1200
shot 08-reader-controls 3
$UI text Display
shot 09-reader-display 3
adb shell input keyevent KEYCODE_BACK
sleep 2
$UI text Chapters
shot 10-reader-chapters 4
adb shell input keyevent KEYCODE_BACK
sleep 2
adb shell input keyevent KEYCODE_BACK
sleep 2
adb shell input keyevent KEYCODE_BACK
shot 11-back-to-novel 4
$UI text Download
shot 12-download-menu 2
$UI contains "Next 10"
shot 13-downloading 10
adb shell input keyevent KEYCODE_BACK
sleep 2
adb shell input keyevent KEYCODE_BACK
sleep 2
$UI text Library
shot 14-library 4
$UI text More
shot 15-more 3
$UI contains "Network"
shot 16-dns 3
$UI contains "Test all"
shot 17-dns-tested 8
adb shell input keyevent KEYCODE_BACK
sleep 1
$UI text Discover
sleep 1
$UI contains "Search all"
sleep 2
adb shell input text "shadow%sslave"
adb shell input keyevent KEYCODE_ENTER
shot 18-search 15
$UI text FreeWebNovel
shot 19-fwn 12
$UI cover 400
shot 20-fwn-novel 12
$UI contains "Start reading"
shot 21-fwn-reader 12
adb shell input tap 540 1200
$UI text Listen
shot 22-tts 6

adb logcat -d > "$OUT/logcat-full.txt"
grep -E "AndroidRuntime|FATAL|$PKG|System.err" "$OUT/logcat-full.txt" | tail -400 > "$OUT/logcat-app.txt"
adb shell pidof $PKG > "$OUT/pid.txt" || echo "app not running" > "$OUT/pid.txt"
exit 0
