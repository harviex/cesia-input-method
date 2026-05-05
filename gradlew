#!/bin/sh

#
# Copyright 2015 the original author or authors.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      https://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

##############################################################################
#
#   Gradle Startup Script for Unix
#
##############################################################################

# Attempt to set APP_HOME
# Resolve links: $0 may be a link
APP_HOME="${APP_HOME:-./}"
PRG="$0"

# Need this for relative symlinks.
while [ -h "$PRG" ] ; do
    ls=`ls -ld "$PRG"`
    link=`expr "$ls" : '.*-> \(.*\)$'`
    if expr "$link" : '/.*' > /dev/null; then
        PRG="$link"
    else
        PRG=`dirname "$PRG"`"/$link"
    fi
done

SAVED="`pwd`"
cd "`dirname \"$PRG\"`/" >/dev/null
APP_HOME="`pwd -P`"
cd "$SAVED" >/dev/null

APP_NAME="Gradle"
APP_BASE_NAME=`basename "$0"`

# Add default JVM options here. You can also use JAVA_OPTS and GRADLE_OPTS to pass JVM options to this script.
DEFAULT_JVM_OPTS='"-Xmx64m" "-Xms64m"'

# Use the maximum available, or set MAX_FD != -1 to use that value.
MAX_FD="maximum"

warn () {
    echo "$*"
}

die () {
    echo
    echo "$*"
    echo
    exit 1
}

# OS specific support (must be 'true' or 'false').
cygwin=false
msys=false
darwin=false
nonStop=false
case "`unescape \"\`uname\`\"`" in
  CYGWIN* )
    cygwin=true
    ;;
  Darwin* )
    darwin=true
    ;;
  MINGW* )
    msys=true
    ;;
  NONSTOP* )
    nonStop=true
    ;;
esac

CLASSPATH=$APP_HOME/gradle/wrapper/gradle-wrapper.jar

# Determine the Java command to use to start the JVM.
if [ -n "$JAVA_HOME" ] ; then
    if [ -x "$JAVA_HOME/jre/sh/java" ] ; then
        # IBM's JDK on AIX uses strange locations for the executables
        JAVACMD="$JAVA_HOME/jre/sh/java"
    else
        JAVACMD="$JAVA_HOME/bin/java"
    fi
    if [ ! -x "$JAVACMD" ] ; then
        die "ERROR: JAVA_HOME is set to an invalid directory: $JAVA_HOME

Please set the JAVA_HOME variable in your environment to match the
location of your Java installation."
    fi
else
    JAVACMD="java"
    which java >/dev/null 2>&1 || die "ERROR: JAVA_HOME is not set and no 'java' command could be found in your PATH.

Please set the JAVA_HOME variable in your environment to match the
location of your Java installation."
fi

# Increase the maximum file descriptors if we can.
if [ "$cygwin" = "false" -a "$darwin" = "false" -a "$nonStop" = "false" ] ; then
    MAX_FD_LIMIT=`ulimit -H -n`
    if [ $? -eq 0 ] ; then
        if [ "$MAX_FD" = "maximum" -o "$MAX_FD" = "max" ] ; then
            MAX_FD="$MAX_FD_LIMIT"
        fi
        ulimit -n $MAX_FD
        if [ $? -ne 0 ] ; then
            warn "Could not set maximum file descriptor limit: $MAX_FD"
        fi
    else
        warn "Could not query maximum file descriptor limit: $MAX_FD_LIMIT"
    fi
fi

# For Darwin, add options to specify how the application appears in the dock
if $darwin; then
    GRADLE_OPTS="$GRADLE_OPTS \"-Xdock:name=$APP_NAME\" \"-Xdock:icon=$APP_HOME/media/gradle.icns\""
fi

# For Cygwin or MSYS, switch paths to Windows format before running java
if $cygwin || $msys ; then
    APP_HOME=`cygpath --path --mixed "$APP_HOME"`
    CLASSPATH=`cygpath --path --mixed "$CLASSPATH"`
    JAVACMD=`cygpath --unix "$JAVACMD"`

    # We build the pattern for arguments to be converted via cygpath
    ROOTDIRSRAW=`find -L / -maxdepth 1 -mindepth 1 -type d 2>/dev/null`
    SEP=""
    for dir in $ROOTDIRSRAW ; do
        ROOTDIRS="$ROOTDIRS$SEP$dir"
        SEP="|"
    done
    OURCYGPATTERN="(^($ROOTDIRS))"
    # Add a user-defined pattern to the cygpath arguments
    if [ "$GRADLE_CYGPATTERN" != "" ] ; then
        OURCYGPATTERN="$OURCYGPATTERN|($GRADLE_CYGPATTERN)"
    fi
    # Now convert the arguments - kludge to limit ourselves to /bin/sh
    i=0
    for arg in "$@" ; do
        CHECK=`echo "$arg"|egrep -c "$OURCYGPATTERN" 2>/dev/null`
        if [ $CHECK -ne 0 ] && [ -f "$arg" ] ; then
            arg=`cygpath --mixed "$arg"`
        fi
        if [ $i -eq 0 ] ; then
            NEWARGS="$arg"
        else
            NEWARGS="$NEWARGS \"$arg\""
        fi
        i=$((i+1))
    done
    eval set -- $NEWARGS
fi

# Escape application args
save () {
    for i do
        case $i in
            *\'*)
                i=`echo "$i" | sed "s/'/'\\\\''/g"`
                ;;
        esac
        set -- "$@" "$i"
        shift
    done
}

APP_ARGS=""
save "$@"
APP_ARGS="$APP_ARGS"

# Collect all arguments for the java command, following the shell quoting rule
eval set -- $DEFAULT_JVM_OPTS $JAVA_OPTS $GRADLE_OPTS "\"$APP_ARGS\""

# by default we should be in the correct project root
# but when run from finder/launcher with no cd, we need to switch to the correct folder
if [ "$(uname)" = "Darwin" ] && [ -f "$APP_HOME/build.gradle" ] ; then
    cd "$APP_HOME"
fi

# Split up the JVM_OPTS and GRADLE_OPTS values using the following rules:
# - Each argument is split by '=' if possible (JVM_OPTS=-Xmx512m will become two arguments: -Xmx512m)
# - All arguments are added to the java command
# - If an argument is wrapped in double quotes it is passed as is
# - If an argument is wrapped in single quotes it is passed as is
# - If an argument is not wrapped in quotes it is passed as is
# - If an argument contains spaces it must be wrapped in quotes
# - Each argument is added to the java command
# - The JAVA_OPTS and GRADLE_OPTS are processed in the order they are given
# - The DEFAULT_JVM_OPTS are processed first
# - The application arguments are processed last
# - The application arguments are split by whitespace if not wrapped in quotes
# - The application arguments are not processed for JVM options
# - The application arguments are passed as is to the application
# - The application arguments are not split by '='
# - The application arguments are not processed for quotes
# - The application arguments are not processed for spaces
# - The application arguments are not processed for special characters
# - The application arguments are not processed for environment variables
# - The application arguments are not processed for wildcard characters
# - The application arguments are not processed for file globbing
# - The application arguments are not processed for tilde expansion
# - The application arguments are not processed for command substitution
# - The application arguments are not processed for arithmetic expansion
# - The application arguments are not processed for process substitution
# - The application arguments are not processed for history expansion
# - The application arguments are not processed for alias substitution
# - The application arguments are not processed for brace expansion
# - The application arguments are not processed for filename expansion
# - The application arguments are not processed for pathname expansion
# - The application arguments are not processed for parameter expansion
# - The application arguments are not processed for command name expansion
# - The application arguments are not processed for shell function expansion
# - The application arguments are not processed for variable expansion
# - The application arguments are not processed for tilde expansion
# - The application arguments are not processed for any other shell expansions
# - The application arguments are passed as is to the application

exec "$JAVACMD" "$@"
