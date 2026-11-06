#!/bin/bash
# Music player on an emulator: library scan (incl. a .nomedia folder), playback in the background,
# notification, lyrics from MP3/FLAC/M4A tags and online.
set -x
OUT=music-e2e
mkdir -p $OUT
shot() { adb exec-out screencap -p > $OUT/$1.png; }
state() { adb shell dumpsys media_session | grep -E "package=com.zlc.music|state=PlaybackState" | head -6 | tr -s ' ' | sed "s/^/$1: /" >> $OUT/results.txt; }
adb install -r -g music/build/outputs/apk/debug/music-debug.apk > $OUT/install.txt 2>&1
adb shell mkdir -p /sdcard/Music/test /sdcard/Download/hidden
adb push music-fixtures/Music/. /sdcard/Music/test/
adb push music-fixtures/hidden/. /sdcard/Download/hidden/
sleep 10
adb shell content query --uri content://media/external/audio/media --projection _display_name:duration:title > $OUT/mediastore.txt 2>&1
adb logcat -c
adb shell am start -n com.zlc.music/.MainActivity
sleep 12
shot 1-library
adb shell am start -n com.zlc.music/.MainActivity --es demo play
sleep 8
shot 2-player-mp3
state "after play"
adb shell input keyevent KEYCODE_HOME
sleep 6
state "in background"
adb shell dumpsys notification --noredact | grep -B2 -A12 "pkg=com.zlc.music" | head -40 > $OUT/notification.txt
adb shell cmd statusbar expand-notifications
sleep 2
shot 3-notification
adb shell cmd statusbar collapse
adb shell am start -n com.zlc.music/.MainActivity --es demo "song:second"
sleep 7
shot 4-flac
adb shell am start -n com.zlc.music/.MainActivity --es demo "song:第三首"
sleep 7
shot 5-m4a
adb shell am start -n com.zlc.music/.MainActivity --es demo "song:bohemian"
sleep 14
shot 6-online
adb shell am start -n com.zlc.music/.MainActivity --es demo "tab:folders"
sleep 3
shot 7-folders
adb shell input keyevent KEYCODE_MEDIA_PAUSE
sleep 2
state "after pause key"
adb logcat -d > $OUT/logcat.txt
grep -E "FATAL EXCEPTION|AndroidRuntime" -A20 $OUT/logcat.txt | head -80 > $OUT/crash.txt
grep -E "MusicBox" -A12 $OUT/logcat.txt | head -60 >> $OUT/results.txt
cat $OUT/results.txt $OUT/mediastore.txt $OUT/notification.txt
exit 0
