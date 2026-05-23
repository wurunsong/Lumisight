#!/usr/bin/env bash
set -euo pipefail

export JAVA_HOME="/Users/lilac/Library/Java/JavaVirtualMachines/jbr-21.0.8/Contents/Home"
export PATH="$JAVA_HOME/bin:$PATH"

echo "JAVA_HOME=$JAVA_HOME"
java -version
mvn -v | sed -n '1,6p'
