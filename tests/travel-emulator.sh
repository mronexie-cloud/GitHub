#!/bin/bash
# Travel guide on an emulator: planner, photos, map tiles, weather (through the app's network bridge),
# GPS starting point, place page and back button.
set -x
OUT=travel-e2e
mkdir -p $OUT
shot() { adb exec-out screencap -p > $OUT/$1.png; }
adb install -r -g travel/build/outputs/apk/debug/travel-debug.apk > $OUT/install.txt 2>&1
adb logcat -c
adb shell am start -n com.zlc.travel/.MainActivity
sleep 10
shot 1-form
adb shell am start -n com.zlc.travel/.MainActivity --es demo "plan:吉隆坡:3"
sleep 12
shot 2-plan-kl
adb shell input swipe 540 1800 540 600 400
sleep 2
shot 3-plan-kl-day1
# GPS in Penang, then plan from "my location"
adb emu geo fix 100.3288 5.4141
sleep 3
adb emu geo fix 100.3290 5.4142
adb shell am start -n com.zlc.travel/.MainActivity --es demo "locate"
sleep 12
shot 4-located
adb shell am start -n com.zlc.travel/.MainActivity --es demo "go:4"
sleep 12
shot 5-plan-from-gps
adb shell am start -n com.zlc.travel/.MainActivity --es demo "place:gardensbay"
sleep 6
shot 6-place
adb shell input keyevent KEYCODE_BACK
sleep 2
shot 7-after-back
adb shell am start -n com.zlc.travel/.MainActivity --es demo "tab:pExplore"
sleep 5
shot 8-explore
adb shell am start -n com.zlc.travel/.MainActivity --es demo "plan:新山:5"
sleep 12
shot 9-plan-jb
adb shell run-as com.zlc.travel ls cache/tiles > $OUT/tiles.txt 2>&1
echo "tiles cached: $(wc -l < $OUT/tiles.txt)" >> $OUT/results.txt
adb shell am start -n com.zlc.travel/.MainActivity --es demo "route:bali5"
sleep 6
shot 10-route
adb logcat -d > $OUT/logcat.txt
grep -E "Travel|chromium" $OUT/logcat.txt | head -100 > $OUT/app-log.txt
grep -E "FATAL EXCEPTION|AndroidRuntime" -A20 $OUT/logcat.txt | head -80 > $OUT/crash.txt
