#!/bin/sh

# Minimal Gradle wrapper launcher generated for this project.

app_path=$0

while
    APP_HOME=${app_path%"${app_path##*/}"}
    [ -h "$app_path" ]
do
    link=$(ls -ld "$app_path")
    link=${link#*' -> '}
    case $link in
      /*) app_path=$link ;;
      *)  app_path=$APP_HOME$link ;;
    esac
done

APP_HOME=$(cd -P "${APP_HOME:-./}" >/dev/null && pwd) || exit
APP_NAME=${0##*/}
CLASSPATH=$APP_HOME/gradle/wrapper/gradle-wrapper.jar

if [ -n "$JAVA_HOME" ]; then
    JAVACMD=$JAVA_HOME/bin/java
    if [ ! -x "$JAVACMD" ]; then
        echo "ERROR: JAVA_HOME points to an invalid Java installation: $JAVA_HOME" >&2
        exit 1
    fi
else
    JAVACMD=java
    if ! command -v java >/dev/null 2>&1; then
        echo "ERROR: JAVA_HOME is not set and java was not found on PATH." >&2
        exit 1
    fi
fi

exec "$JAVACMD" \
    -Xmx64m -Xms64m \
    "-Dorg.gradle.appname=$APP_NAME" \
    -classpath "$CLASSPATH" \
    org.gradle.wrapper.GradleWrapperMain \
    "$@"

