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
  for i in $(seq 1 30); do
    sleep 5
    [ $i -eq 2 ] && adb exec-out screencap -p > $OUT/$name-mid.png
    n=$(count)
    [ "$n" -ge "$want" ] && break
  done
  adb exec-out screencap -p > $OUT/$name.png
  echo "RESULT $name: gallery videos=$n (wanted $want) after $(( $(date +%s) - start ))s" | tee -a $OUT/results.txt
}
# Douyin: the exact share text from a user's report, then a second video
run douyin-share "7.69 复制打开抖音，看看【小马的深圳生活🐱的作品】相信光 不要光站在那里 要站在光里 站在光里输光光... https://v.douyin.com/WBYkFPmGFEg/ 09/01 :2pm IVl:/ e@o.Qk" 1
n=$(count); run douyin "7.89 复制打开抖音，看看 https://www.douyin.com/video/6961737553342991651 复制此链接" $((n+1))
n=$(count); run instagram "https://www.instagram.com/reel/Chunk8-jurw/" $((n+1))
n=$(count); run x "https://twitter.com/captainamerica/status/719944021058060289" $((n+1))
n=$(count); run tiktok "https://www.tiktok.com/@barudakhb_/video/6984138651336838402" $((n+1))
adb shell content query --uri content://media/external/video/media --projection _display_name:_size:relative_path > $OUT/gallery.txt 2>&1
adb logcat -d > $OUT/logcat.txt
grep -E " VDL" $OUT/logcat.txt > $OUT/vdl.txt || true
# one frame from each Douyin video, to check by eye that there is no watermark
mkdir -p pulled && adb pull "/sdcard/Movies/万能下载/" pulled/ > /dev/null 2>&1 || true
python3 -m pip install -q --break-system-packages imageio-ffmpeg > /dev/null 2>&1 || python3 -m pip install -q imageio-ffmpeg > /dev/null 2>&1 || true
FF=$(python3 -c "import imageio_ffmpeg;print(imageio_ffmpeg.get_ffmpeg_exe())" 2>/dev/null)
i=0
find pulled -name "*.mp4" | sort | while read -r f; do
  i=$((i+1))
  for ss in 1.5 6; do "$FF" -y -loglevel error -ss $ss -i "$f" -frames:v 1 -vf scale=300:-2 "$OUT/frame-$i-$ss.jpg" || true; done
  echo "frame-$i: $(basename "$f") $("$FF" -i "$f" 2>&1 | grep -oE "Duration: [0-9:.]+|Video: [a-z0-9]+[^,]*, [^,]*, [0-9]+x[0-9]+" | tr '\n' ' ')" >> $OUT/frames.txt
done
grep -E "FATAL EXCEPTION|AndroidRuntime|com.zlc.vdl" $OUT/logcat.txt | head -60 > $OUT/crash.txt
cat $OUT/results.txt $OUT/gallery.txt $OUT/vdl.txt $OUT/frames.txt
exit 0
