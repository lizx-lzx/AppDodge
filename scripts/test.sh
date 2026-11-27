#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
JDK="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}"
mkdir -p build/policy-tests
SRC=app/src/main/java/dev/local/appdodge
"$JDK/bin/javac" -encoding UTF-8 -sourcepath app/src/main/java -d build/policy-tests \
    "$SRC/Rule.java" tests/PolicyTests.java tests/native-rest-smoke/RestPeriodsTest.java
"$JDK/bin/java" -cp build/policy-tests dev.local.appdodge.PolicyTests
"$JDK/bin/java" -cp build/policy-tests dev.local.appdodge.RestPeriodsTest
