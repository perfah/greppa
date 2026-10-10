#!/usr/bin/env bash
set -euo pipefail

# Build script for the Antiviber JetBrains plugin.
# Run this from the antiviber/ directory.

IDEA_HOME="${IDEA_HOME:-/opt/RustRover}"
JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/default-java}"

if [ ! -d "$IDEA_HOME/lib" ]; then
    echo "ERROR: IDEA_HOME/lib does not exist: $IDEA_HOME/lib" >&2
    echo "Set IDEA_HOME to your local JetBrains IDE installation, e.g.:" >&2
    echo "  export IDEA_HOME=/opt/RustRover" >&2
    exit 1
fi

if [ ! -x "$JAVA_HOME/bin/javac" ]; then
    echo "ERROR: javac not found at $JAVA_HOME/bin/javac" >&2
    exit 1
fi

echo "Discovering IntelliJ Platform jars..."
mapfile -t jars < <(find "$IDEA_HOME/lib" -name '*.jar' | sort)

if [ ${#jars[@]} -eq 0 ]; then
    echo "ERROR: No jars found under $IDEA_HOME/lib" >&2
    exit 1
fi

echo "Found ${#jars[@]} jars"

# Build a classpath jar in the project directory using absolute file URLs.
classpathJar="ide-classpath.jar"
manifestFile="ide-classpath.manifest"

entriesFile=$(mktemp)
trap 'rm -f "$entriesFile"' EXIT
printf '%s\n' "${jars[@]}" > "$entriesFile"

python3 - "$entriesFile" "$manifestFile" <<'PY'
import sys
import urllib.parse

entries_path = sys.argv[1]
manifest_path = sys.argv[2]

with open(entries_path, 'r', encoding='utf-8') as f:
    jars = [line.rstrip('\n') for line in f if line.strip()]

entries = []
for jar in jars:
    path = jar.replace('\\', '/')
    url = 'file:///' + urllib.parse.quote(path, safe='/:@')
    entries.append(url)

# Build the full Class-Path attribute value.
value = 'Class-Path: ' + ' '.join(entries)

# JAR manifest lines are limited to 72 bytes. Continuation lines start with
# a single space that is stripped by the parser, so reserve one byte for it.
max_first = 72
max_cont = 71

lines = []
offset = 0
first = True
value_bytes = value.encode('ascii')
while offset < len(value_bytes):
    take = max_first if first else max_cont
    chunk = value_bytes[offset:offset + take].decode('ascii')
    if first:
        lines.append(chunk)
    else:
        lines.append(' ' + chunk)
    offset += take
    first = False

# Manifest files must end with a blank line.
lines.append('')

with open(manifest_path, 'w', encoding='ascii', newline='\r\n') as f:
    f.write('\r\n'.join(lines))
    f.write('\r\n')
PY

"$JAVA_HOME/bin/jar" cfm "$classpathJar" "$manifestFile"

mvn -Didea.classpath.jar="$classpathJar" -Dmaven.compiler.fork=true clean package

echo ""
echo "Build complete. Install the plugin from:"
echo "  antiviber/target/antiviber-1.0.0.jar"
