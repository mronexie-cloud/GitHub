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
# food tab, navigation from the GPS position (KL) to the Petronas towers, following, Grab
adb shell am start -n com.zlc.travel/.MainActivity --es demo "tab:pFood"
sleep 6
shot 11-food
adb emu geo fix 101.7120 3.1505
sleep 2
adb emu geo fix 101.7121 3.1506
adb shell am start -n com.zlc.travel/.MainActivity --es demo "nav:petronas"
sleep 15
shot 12-nav
adb shell am start -n com.zlc.travel/.MainActivity --es demo "follow"
sleep 3
adb emu geo fix 101.7116 3.1530
sleep 5
shot 13-follow
adb shell am start -n com.zlc.travel/.MainActivity --es demo "place:yaowarat"
sleep 5
shot 14-food-place
adb shell am start -n com.zlc.travel/.MainActivity --es demo "grab:petronas"
sleep 6
shot 15-grab
adb logcat -d -s Travel:* ActivityTaskManager:I > $OUT/grab-log.txt
adb shell input keyevent KEYCODE_BACK
sleep 2
adb shell am start -n com.zlc.travel/.MainActivity --es demo "place:tioman"
sleep 6
shot 16-island
adb shell am start -n com.zlc.travel/.MainActivity --es demo "ferry:batam-hf"
sleep 5
shot 17-ferry
# v1.3: edit the plan (swap, move), costs, the trip in the phone's calendar, the .ics file
adb shell am start -n com.zlc.travel/.MainActivity --es demo "plan:吉隆坡:3"
sleep 12
adb shell am start -n com.zlc.travel/.MainActivity --es demo "edit"
sleep 3
shot 20-edit-sheet
adb shell am start -n com.zlc.travel/.MainActivity --es demo "swap"
sleep 3
shot 21-swap-choices
adb shell am start -n com.zlc.travel/.MainActivity --es demo "pick"
sleep 4
shot 22-after-swap
adb shell am start -n com.zlc.travel/.MainActivity --es demo "move"
sleep 4
shot 23-after-move
adb shell am start -n com.zlc.travel/.MainActivity --es demo "cost"
sleep 3
shot 24-cost
adb shell am start -n com.zlc.travel/.MainActivity --es demo "cal"
sleep 3
shot 25-cal-sheet
adb logcat -c
adb shell am start -n com.zlc.travel/.MainActivity --es demo "caladd"
sleep 8
shot 26-calendar-app
adb logcat -d -s Travel:* > $OUT/calendar-log.txt
adb shell content query --uri content://com.android.calendar/events --projection title:dtstart:eventTimezone > $OUT/calendar-events.txt 2>&1
echo "calendar events: $(grep -c 'Row:' $OUT/calendar-events.txt)" >> $OUT/results.txt
adb shell am start -n com.zlc.travel/.MainActivity --es demo "caladd"
sleep 8
adb shell content query --uri content://com.android.calendar/events --projection title:dtstart:eventTimezone:deleted > $OUT/calendar-events-2.txt 2>&1
echo "calendar events after adding again: $(grep -c 'Row:' $OUT/calendar-events-2.txt) (deleted=0: $(grep -c 'deleted=0' $OUT/calendar-events-2.txt))" >> $OUT/results.txt
adb shell am start -n com.zlc.travel/.MainActivity --es demo "calshare"
sleep 4
shot 27-share-ics
adb shell input keyevent KEYCODE_BACK
sleep 2
adb shell am start -n com.zlc.travel/.MainActivity --es demo "addplace:sunwaylagoon"
sleep 4
shot 28-add-place
adb shell run-as com.zlc.travel ls cache/tiles > $OUT/tiles.txt 2>&1
echo "tiles cached: $(wc -l < $OUT/tiles.txt)" >> $OUT/results.txt
adb shell am start -n com.zlc.travel/.MainActivity --es demo "route:bali5"
sleep 6
shot 10-route
adb logcat -d > $OUT/logcat.txt
grep -E "Travel|chromium" $OUT/logcat.txt | head -100 > $OUT/app-log.txt
grep -E "FATAL EXCEPTION|AndroidRuntime" -A20 $OUT/logcat.txt | head -80 > $OUT/crash.txt
