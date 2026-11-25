#!/bin/sh
# Starts the Skill Exchange programs on macOS and Linux.
#
#   ./run.sh            the desktop application (starts its own server if none is running)
#   ./run.sh server     only the server, with its admin console
#   ./run.sh demo       the concurrency demonstration in the terminal
#   ./run.sh test       the JUnit tests
#   ./run.sh package    builds target/skill-exchange.jar, one file that runs anywhere with Java 17+
#
# Anything after the first word is passed on, for example:  ./run.sh server --reset
#                                                           ./run.sh app --host=192.168.1.20

cd "$(dirname "$0")" || exit 1

# The project needs JDK 17 or newer. If "java" is older, look for a newer one in the usual places.
major() { "$1" -version 2>&1 | sed -n 's/.*version "\([0-9]*\).*/\1/p' | head -1; }
if [ -z "$JAVA_HOME" ] || [ "$(major "$JAVA_HOME/bin/java")" -lt 17 ] 2>/dev/null; then
    for candidate in \
        "$(/usr/libexec/java_home -v 17+ 2>/dev/null)" \
        /opt/homebrew/opt/openjdk@21 /opt/homebrew/opt/openjdk@17 /opt/homebrew/opt/openjdk \
        /usr/local/opt/openjdk@21 /usr/local/opt/openjdk@17 /usr/local/opt/openjdk \
        /usr/lib/jvm/java-21-openjdk* /usr/lib/jvm/java-17-openjdk*; do
        if [ -n "$candidate" ] && [ -x "$candidate/bin/java" ] && [ "$(major "$candidate/bin/java")" -ge 17 ] 2>/dev/null; then
            JAVA_HOME="$candidate"
            break
        fi
    done
    export JAVA_HOME
fi
if [ -z "$JAVA_HOME" ] || [ ! -x "$JAVA_HOME/bin/java" ]; then
    echo "JDK 17 or newer was not found. Install one and set JAVA_HOME to it." >&2
    exit 1
fi

mode="${1:-app}"
[ $# -gt 0 ] && shift

case "$mode" in
    app)    exec ./mvnw -q compile javafx:run -Djavafx.args="$*" ;;
    server) exec ./mvnw -q compile exec:java -Dexec.mainClass=skillexchange.server.ServerMain -Dexec.args="$*" ;;
    demo)   exec ./mvnw -q compile exec:java -Dexec.mainClass=skillexchange.Launcher -Dexec.args="demo" ;;
    test)   exec ./mvnw test ;;
    package) exec ./mvnw -q package -Pall-platforms ;;
    *)      echo "Usage: ./run.sh [app|server|demo|test|package] [options]" >&2; exit 2 ;;
esac
