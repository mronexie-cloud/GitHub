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
# v1.4: public transport from Transitous (navigation page from KL Sentral, and a leg of a public-transport plan)
adb emu geo fix 101.6869 3.1340
sleep 2
adb emu geo fix 101.6870 3.1341
adb shell am start -n com.zlc.travel/.MainActivity --es demo "navt:petronas:3.1334,101.6865"
sleep 15
shot 30-nav-transit
adb shell input swipe 540 1700 540 700 400
sleep 2
shot 31-nav-transit-list
adb shell am start -n com.zlc.travel/.MainActivity --es demo "planpub:吉隆坡:2"
sleep 12
adb shell am start -n com.zlc.travel/.MainActivity --es demo "transit"
sleep 12
shot 32-plan-transit
adb logcat -d -s Travel:* | grep -i transit > $OUT/transit-log.txt
echo "transit answers: $(grep -c 'transit ' $OUT/transit-log.txt)" >> $OUT/results.txt
# v1.5: the place in Google Maps (rating, reviews, photos)
adb shell am start -n com.zlc.travel/.MainActivity --es demo "place:jonker"
sleep 5
adb shell input swipe 540 1700 540 900 400
sleep 2
shot 33-place-google-button
adb shell am start -n com.zlc.travel/.MainActivity --es demo "gmaps:petronas"
sleep 10
shot 34-google-maps
adb logcat -d -s ActivityTaskManager:I | grep -iE "google|maps|chrome" | tail -5 > $OUT/gmaps-log.txt
adb shell input keyevent KEYCODE_BACK
sleep 2
# v1.6: from A to B, and nearby places from near to far
adb shell am start -n com.zlc.travel/.MainActivity --es demo "ab:吉隆坡:槟城:3"
sleep 12
shot 35-ab-plan
adb emu geo fix 101.5183 3.0738
sleep 2
adb emu geo fix 101.5184 3.0739
adb shell am start -n com.zlc.travel/.MainActivity --es demo "near"
sleep 8
shot 36-near
adb shell input swipe 540 1700 540 600 400
sleep 2
shot 37-near-more
# v1.7: KTV, bars, restaurants, duty-free
adb shell am start -n com.zlc.travel/.MainActivity --es demo "near:bar"
sleep 6
shot 38-near-bars
adb shell am start -n com.zlc.travel/.MainActivity --es demo "place:celavi"
sleep 5
adb shell input swipe 540 1700 540 700 400
sleep 2
shot 39-bar-page
adb shell am start -n com.zlc.travel/.MainActivity --es demo "near:dutyfree"
sleep 6
shot 40-near-dutyfree
# v1.8: a place shared from Google Maps (分享 → 东南亚旅游攻略), saved to 我的地点; "more on Google Maps" in Explore
adb shell "am start -a android.intent.action.SEND -t text/plain -n com.zlc.travel/.MainActivity --es android.intent.extra.TEXT 'Village Park Restaurant https://www.google.com/maps/place/Village+Park+Restaurant/@3.1361,101.6187,17z/data=!3m1!4b1!4m6!3m5!8m2!3d3.1361!4d101.6187'"
sleep 6
shot 41-shared-confirm
adb shell am start -n com.zlc.travel/.MainActivity --es demo "mineok"
sleep 5
shot 42-my-place
adb shell am start -n com.zlc.travel/.MainActivity --es demo "tab:pMine"
sleep 4
shot 43-my-places
# only an address copied from Google Maps (no link): looked up step by step, then fixed on the map
adb shell "am start -a android.intent.action.SEND -t text/plain -n com.zlc.travel/.MainActivity --es android.intent.extra.TEXT 'F-02-18A, Level 2, Commercial Street, Mahkota Selatan, Pulau Satu, 81550 Gelang Patah, Johor'"
sleep 20
shot 46-address-only
# v1.9: KTV, bars, restaurants and duty-free within 100 km, live from OpenStreetMap (GPS is in Shah Alam here)
adb shell am start -n com.zlc.travel/.MainActivity --es demo "near:ktv"
sleep 40
shot 44-near-ktv
adb shell am start -n com.zlc.travel/.MainActivity --es demo "near:dining"
sleep 30
shot 47-near-dining
adb shell input swipe 540 1700 540 500 400
sleep 2
shot 48-near-dining-list
adb shell am start -n com.zlc.travel/.MainActivity --es demo "near:bar"
sleep 40
shot 49-near-bars-live
adb shell am start -n com.zlc.travel/.MainActivity --es demo "near:dutyfree"
sleep 30
shot 50-near-dutyfree-live
adb logcat -d -s Travel:* | grep -i "live " > $OUT/live-log.txt
cat $OUT/live-log.txt >> $OUT/results.txt
adb shell am start -n com.zlc.travel/.MainActivity --es demo "place:kruaapsorn"
sleep 5
adb shell input swipe 540 1700 540 700 400
sleep 2
shot 45-restaurant-page
adb shell run-as com.zlc.travel ls cache/tiles > $OUT/tiles.txt 2>&1
echo "tiles cached: $(wc -l < $OUT/tiles.txt)" >> $OUT/results.txt
adb shell am start -n com.zlc.travel/.MainActivity --es demo "route:bali5"
sleep 6
shot 10-route
adb logcat -d > $OUT/logcat.txt
grep -E "Travel|chromium" $OUT/logcat.txt | head -100 > $OUT/app-log.txt
grep -E "FATAL EXCEPTION|AndroidRuntime" -A20 $OUT/logcat.txt | head -80 > $OUT/crash.txt
