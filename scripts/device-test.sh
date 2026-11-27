#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
serial="${1:-emulator-5580}"
case "$serial" in emulator-*) ;; *) echo 'Use an owned emulator with AppDodge’s accessibility service enabled, not a personal phone.'; exit 1 ;; esac
python3 scripts/build.py --device-tests
adb -s "$serial" install -r "$(python3 scripts/build.py --apk-path)"
adb -s "$serial" install -r output/appdodge-device-tests.apk
adb -s "$serial" shell am instrument -w dev.local.appdodge.test/dev.local.appdodge.RuleLockDeviceTests > build/device-tests.txt
cat build/device-tests.txt
python3 -c 'from pathlib import Path; assert "\nPASS:" in Path("build/device-tests.txt").read_text(), "Device tests failed"'
