#!/usr/bin/env bash
set -euo pipefail

# This installs and resets ONLY the separate verification application.
cd "$(dirname "$0")/.."
serial="${1:?Usage: verify-device.sh DEVICE_SERIAL}"
sdk="${ANDROID_HOME:?Set ANDROID_HOME to your Android SDK}"
adb="$sdk/platform-tools/adb"
package="com.gyftalala.omni.verification"
runner="$package.test/com.gyftalala.omni.OmniTestRunner"
out="build/verification"
mkdir -p "$out"

./gradlew -PomniVerification=true :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
"$sdk/build-tools/34.0.0/aapt" dump badging app/build/outputs/apk/debug/app-debug.apk > "$out/apk-info.txt"
rg -q "package: name='$package'" "$out/apk-info.txt"
"$adb" -s "$serial" install -r app/build/outputs/apk/debug/app-debug.apk
"$adb" -s "$serial" install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
"$adb" -s "$serial" shell appops set "$package" SCHEDULE_EXACT_ALARM allow
"$adb" -s "$serial" shell appops set "$package" POST_NOTIFICATION allow
api="$("$adb" -s "$serial" shell getprop ro.build.version.sdk | tr -d '\r')"
if (( api >= 33 )); then "$adb" -s "$serial" shell pm grant "$package" android.permission.POST_NOTIFICATIONS; fi

run_test() {
    local name="$1"
    shift
    "$adb" -s "$serial" shell am instrument -w -r "$@" "$runner" > "$out/$name.txt"
    rg -q '^OK \(' "$out/$name.txt" || { cat "$out/$name.txt"; return 1; }
    rg '^OK \(|^Time:' "$out/$name.txt"
}

run_test use-cases -e notClass com.gyftalala.omni.DeniedPermissionsTest,com.gyftalala.omni.ProcessRecoveryTest

# Revoking exact-alarm access can kill the target process; do this before launch.
restore_permissions() {
    "$adb" -s "$serial" shell appops set "$package" SCHEDULE_EXACT_ALARM allow
    "$adb" -s "$serial" shell appops set "$package" POST_NOTIFICATION allow
    if (( api >= 33 )); then "$adb" -s "$serial" shell pm grant "$package" android.permission.POST_NOTIFICATIONS; fi
}
trap restore_permissions EXIT
"$adb" -s "$serial" shell appops set "$package" SCHEDULE_EXACT_ALARM deny
"$adb" -s "$serial" shell appops set "$package" POST_NOTIFICATION deny
if (( api >= 33 )); then "$adb" -s "$serial" shell pm revoke "$package" android.permission.POST_NOTIFICATIONS; fi
run_test denied-permissions -e class com.gyftalala.omni.DeniedPermissionsTest -e permissionsDenied true
restore_permissions
trap - EXIT

run_test recovery-prepare -e class 'com.gyftalala.omni.ProcessRecoveryTest#prepare' -e recovery true
"$adb" -s "$serial" shell am force-stop "$package"
run_test recovery-after-stop -e class 'com.gyftalala.omni.ProcessRecoveryTest#verifyAfterProcessStop' -e recovery true
"$adb" -s "$serial" pull "/sdcard/Android/data/$package/files/performance.json" "$out/performance.json"
"$adb" -s "$serial" shell am force-stop "$package"
printf 'Verification complete. Reports: %s\n' "$out"
