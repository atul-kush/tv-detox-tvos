#!/bin/sh
# Runs the JVM tests for the home screen's plain-Java classes (no Android needed).
#   sh tests/run.sh
set -e
cd "$(dirname "$0")/.."

[ -n "$JAVA_HOME" ] || for j in /opt/homebrew/opt/openjdk /opt/homebrew/opt/openjdk@17 /usr/local/opt/openjdk; do [ -d "$j" ] && JAVA_HOME=$j && break; done
[ -n "$JAVA_HOME" ] && export PATH="$JAVA_HOME/bin:$PATH"

# org.json is built into Android; the JVM needs the real thing for tests only.
JSON=tests/lib/json-20240303.jar
if [ ! -f "$JSON" ]; then
  mkdir -p tests/lib
  curl -fsSL -o "$JSON" https://repo1.maven.org/maven2/org/json/json/20240303/json-20240303.jar
fi

SRC=src/com/ansub/tvhome
PURE=""
for f in AppLine Picture PictureRules Sources; do [ -f "$SRC/$f.java" ] && PURE="$PURE $SRC/$f.java"; done

rm -rf tests/out; mkdir -p tests/out
# shellcheck disable=SC2046,SC2086 # one argument per file on purpose
javac -nowarn -Xlint:-options -encoding UTF-8 -source 1.8 -target 1.8 -cp "$JSON" -d tests/out \
  tests/Check.java $PURE $(ls tests/*Test.java)

for t in tests/*Test.java; do
  name=$(basename "$t" .java)
  java -ea -cp "tests/out:$JSON" "com.ansub.tvhome.$name"
  echo "PASS $name"
done
