#!/usr/bin/env bash
# Installs the APK on the emulator, walks through the main screens and saves screenshots.
OUT=${1:-shots}
mkdir -p "$OUT"
exec > >(tee -a "$OUT/steps.txt") 2>&1
APK=$(ls apk/*.apk | head -1)
PKG=com.joe.novelreader
UI="python3 .github/scripts/ui.py"

shot() { sleep "${2:-3}"; echo "--- shot $1"; adb exec-out screencap -p > "$OUT/$1.png"; }
back() { adb shell input keyevent KEYCODE_BACK; sleep 2; }

adb install -r "$APK" || exit 1
adb shell pm grant $PKG android.permission.POST_NOTIFICATIONS || true
adb logcat -c
adb shell am start -n $PKG/.MainActivity
shot 01-launch 12
shot 02-discover 12
adb shell input swipe 540 1800 540 700 400
shot 03-discover-scrolled 6
$UI text Browse --at 675 2210
shot 04-browse 4
$UI text NovelBin --at 540 640
shot 05-novelbin 12
$UI cover 400 --at 190 680
shot 06-novel 12
$UI contains "Add to library" --at 253 2247
shot 07-added 3
$UI contains "Start reading" --at 765 2247
shot 08-reader 12
adb shell input tap 540 1300
shot 09-reader-controls 3
$UI text Display --at 900 2250
shot 10-reader-display 3
back
$UI text Chapters --at 180 2250
shot 11-reader-chapters 4
back
adb shell input swipe 540 1800 540 600 300
shot 12-reader-scrolled 3
back
shot 13-back-to-novel 4
$UI text Download --at 870 190
shot 14-download-menu 2
$UI contains "Next 10" --at 700 400
shot 15-downloading 12
back
shot 16-after-back 2
$UI text Library --at 125 2210
shot 17-library 4
$UI text More --at 950 2210
shot 18-more 3
$UI contains "Network" --at 540 450
shot 19-dns 3
$UI contains "Test all" --at 900 900
shot 20-dns-tested 8
back
$UI text Downloads --at 540 300
shot 21-downloads 3
back
$UI text Discover --at 400 2210
sleep 2
$UI contains "Search all" --at 700 210
sleep 3
adb shell input text "martial"
adb shell input keyevent KEYCODE_ENTER
shot 22-search 18
back
back
$UI text Browse --at 675 2210
sleep 2
$UI text FreeWebNovel --at 540 840
shot 23-fwn 12
$UI cover 400 --at 190 680
shot 24-fwn-novel 12
$UI contains "Start reading" --at 765 2247
shot 25-fwn-reader 12
adb shell input tap 540 1300
sleep 2
$UI text Listen --at 540 2250
shot 26-tts 8

adb logcat -d > "$OUT/logcat-full.txt"
grep -E "AndroidRuntime: (FATAL|Process|java|Caused|\s+at )|$PKG|System.err" "$OUT/logcat-full.txt" | grep -v uiautomator | tail -400 > "$OUT/logcat-app.txt"
adb shell pidof $PKG > "$OUT/pid.txt" || echo "app not running" > "$OUT/pid.txt"
exit 0
