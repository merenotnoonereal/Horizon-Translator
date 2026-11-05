# Sourced by build.sh and test/run-test.sh: sets JAR to the JDK's jar tool.
# Windows' javapath shim puts javac on PATH but not jar: fall back to JAVA_HOME, then installed JDKs.
JAR="$(command -v jar || true)"
if [ -z "$JAR" ] && [ -n "${JAVA_HOME:-}" ] && [ -e "$JAVA_HOME/bin/jar" -o -e "$JAVA_HOME/bin/jar.exe" ]; then
  JAR="$JAVA_HOME/bin/jar"
fi
if [ -z "$JAR" ]; then
  for j in /c/Program\ Files/Java/*/bin/jar.exe /usr/lib/jvm/*/bin/jar; do
    [ -e "$j" ] && JAR="$j"
  done
fi
[ -n "$JAR" ] || { echo "Can't find the JDK's jar tool: set JAVA_HOME." >&2; exit 1; }
