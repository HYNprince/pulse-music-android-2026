#!/bin/sh
# Lightweight macOS/Linux bootstrap for the Gradle wrapper when its jar cannot
# be shipped in the generated archive. Downloads the official Gradle 8.13 jar.
set -eu
cd "$(dirname "$0")"
JAR="gradle/wrapper/gradle-wrapper.jar"
if [ ! -s "$JAR" ]; then
  echo "Fetching the official Gradle wrapper bootstrap jar..."
  curl --fail --location --silent --show-error --retry 2 \
    "https://raw.githubusercontent.com/gradle/gradle/v8.13.0/gradle/wrapper/gradle-wrapper.jar" \
    --output "$JAR.tmp"
  if [ ! -s "$JAR.tmp" ]; then
    rm -f "$JAR.tmp"; echo "Wrapper download failed" >&2; exit 1
  fi
  mv "$JAR.tmp" "$JAR"
fi
exec java -cp "$JAR" org.gradle.wrapper.GradleWrapperMain "$@"
