#!/bin/sh
# Maven Wrapper script
# Downloads Maven if not present, then runs it with the given arguments.

set -e
MAVEN_PROJECTBASEDIR=$(dirname "$(readlink -f "$0")")
MAVEN_WRAPPER_JAR="$MAVEN_PROJECTBASEDIR/.mvn/wrapper/maven-wrapper.jar"
MAVEN_WRAPPER_PROPERTIES="$MAVEN_PROJECTBASEDIR/.mvn/wrapper/maven-wrapper.properties"

if [ -r "$MAVEN_WRAPPER_PROPERTIES" ]; then
  DISTRIBUTION_URL=$(grep "distributionUrl" "$MAVEN_WRAPPER_PROPERTIES" | sed 's/distributionUrl=//')
fi

MAVEN_HOME="$HOME/.m2/wrapper/dists/$(basename "$DISTRIBUTION_URL" .zip)"
if [ ! -d "$MAVEN_HOME" ]; then
  mkdir -p "$MAVEN_HOME"
  echo "Downloading Maven from $DISTRIBUTION_URL"
  curl -sSL "$DISTRIBUTION_URL" -o "/tmp/maven.zip"
  unzip -q "/tmp/maven.zip" -d "$MAVEN_HOME"
  rm "/tmp/maven.zip"
fi

MAVEN_BINARY=$(find "$MAVEN_HOME" -name "mvn" -type f | head -1)
exec "$MAVEN_BINARY" "$@"
