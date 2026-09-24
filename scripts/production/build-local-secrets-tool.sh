#!/usr/bin/env bash
set -euo pipefail
repo_dir="$(cd "$(dirname "$0")/../.." && pwd)"
java_bin="${JAVA_HOME:+$JAVA_HOME/bin/}"
output_dir="$repo_dir/target/local-secrets-tool"
source_dir="$repo_dir/platform/rigour-platform-starter/src/main/java/com/rigour/platform/secrets"
mkdir -p "$output_dir/classes"
"${java_bin}javac" --release 21 -encoding UTF-8 -d "$output_dir/classes" \
  "$source_dir/LocalSecretStore.java" "$source_dir/LocalSecretsTool.java"
"${java_bin}jar" --create --file "$output_dir/rigour-local-secrets-tool.jar" \
  --main-class com.rigour.platform.secrets.LocalSecretsTool -C "$output_dir/classes" .
echo "$output_dir/rigour-local-secrets-tool.jar"
