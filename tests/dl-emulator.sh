#!/bin/bash
# Runs inside the Android emulator job: install the downloader, share real links to it, check videos land in the gallery.
set -x
OUT=dl-e2e; mkdir -p $OUT
APK=downloader/build/outputs/apk/debug/downloader-x86_64-debug.apk
ls -la downloader/build/outputs/apk/debug/
adb install -r -g "$APK" > $OUT/install.txt 2>&1; cat $OUT/install.txt
adb shell pm grant com.zlc.vdl android.permission.POST_NOTIFICATIONS || true
adb logcat -c
adb shell am start -n com.zlc.vdl/.MainActivity
sleep 40
adb exec-out screencap -p > $OUT/0-start.png
count() { adb shell content query --uri content://media/external/video/media --projection _display_name:relative_path 2>/dev/null | grep -c "Movies/万能下载"; }
run() {
  name=$1; url=$2; want=$3
  adb shell am start -n com.zlc.vdl/.MainActivity -a android.intent.action.SEND -t text/plain --es android.intent.extra.TEXT "'$url'" --ez autostart true
  start=$(date +%s); n=0
  for i in $(seq 1 48); do
    sleep 5
    n=$(count)
    [ "$n" -ge "$want" ] && break
  done
  adb exec-out screencap -p > $OUT/$name.png
  echo "RESULT $name: gallery videos=$n (wanted $want) after $(( $(date +%s) - start ))s" | tee -a $OUT/results.txt
}
run instagram "https://www.instagram.com/reel/Chunk8-jurw/" 1
run x "https://twitter.com/captainamerica/status/719944021058060289" 2
run facebook "https://www.facebook.com/watch/?v=647537299265662" 3
run douyin "7.89 复制打开抖音，看看 https://www.douyin.com/video/6961737553342991651 复制此链接" 4
adb shell content query --uri content://media/external/video/media --projection _display_name:_size:relative_path > $OUT/gallery.txt 2>&1
adb logcat -d > $OUT/logcat.txt
grep -E "FATAL EXCEPTION|AndroidRuntime|com.zlc.vdl" $OUT/logcat.txt | head -60 > $OUT/crash.txt
cat $OUT/results.txt $OUT/gallery.txt
exit 0
