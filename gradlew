#!/bin/sh

#
# Copyright © 2015-2021 the original authors.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      https://www.apache.org/licenses/LICENSE-2.0
#
# Gradle start up script for UN*X
#

# Attempt to set APP_HOME
# Resolve links: $0 may be a link
app_path=$0

# Need this for daisy-chained symlinks.
while
    APP_HOME=${app_path%"${app_path##*/}"}  # leaves a trailing /; empty if no leading path
    [ -h "$app_path" ]
do
    ls=$( ls -ld "$app_path" )
    link=${ls#*' -> '}
    case $link in             #(
        /*)   app_path=$link ;; #(
        *)    app_path=$APP_HOME$link ;;
    esac
done

APP_HOME=$( cd "${APP_HOME:-./}" && pwd -P ) || exit

APP_NAME="Gradle"
APP_BASE_NAME=${0##*/}

# Add default JVM options here. You can also use JAVA_OPTS and GRADLE_OPTS to pass JVM options to this script.
DEFAULT_JVM_OPTS='"-Xmx64m" "-Xms64m"'

# Use the maximum available, or set MAX_FD != -1 to use that value.
MAX_FD=maximum

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
nonstop=false
case "$( uname )" in                #(
    CYGWIN* )         cygwin=true  ;; #(
    Darwin* )         darwin=true  ;; #(
    MSYS* | MINGW* )  msys=true    ;; #(
    NONSTOP* )        nonstop=true ;;
esac

CLASSPATH=$APP_HOME/gradle/wrapper/gradle-wrapper.jar


# Determine the Java command to use to start the JVM.
if [ -n "$JAVA_HOME" ] ; then
    if [ -x "$JAVA_HOME/jre/sh/java" ] ; then
        # IBM's JDK on AIX uses strange locations for the executables
        JAVACMD=$JAVA_HOME/jre/sh/java
    else
        JAVACMD=$JAVA_HOME/bin/java
    fi
    if [ ! -x "$JAVACMD" ] ; then
        die "ERROR: JAVA_HOME is set to an invalid directory: $JAVA_HOME

Please set the JAVA_HOME variable in your environment to match the
location of your Java installation."
    fi
else
    JAVACMD=java
    which java >/dev/null 2>&1 || die "ERROR: JAVA_HOME is not set and no 'java' command could be found in your PATH.

Please set the JAVA_HOME variable in your environment to match the
location of your Java installation."
fi

# Increase the maximum file descriptors if we can.
if ! "$cygwin" && ! "$darwin" && ! "$nonstop" ; then
    case $MAX_FD in #(
        max*)
            # In POSIX sh, ulimit -H is undefined. That's why the result is checked to see if it worked.
            # shellcheck disable=SC2039,SC3045
            MAX_FD=$( ulimit -H -n ) ||
                warn "Could not query maximum file descriptor limit"
    esac
    case $MAX_FD in  #(
        '' | soft) :;; #(
        *)
            # In POSIX sh, ulimit -n is undefined. That's why the result is checked to see if it worked.
            # shellcheck disable=SC2039,SC3045
            ulimit -n "$MAX_FD" ||
                warn "Could not set maximum file descriptor limit to $MAX_FD"
    esac
fi

# Collect all arguments for the java command, stacking in reverse order:
#   * args from the command line
#   * the main class name
#   * -classpath
#   * -D...appname settings
#   * --module-path (only if needed)
#   * DEFAULT_JVM_OPTS, JAVA_OPTS, and GRADLE_OPTS environment variables.

# For Cygwin or MSYS, switch paths to Windows format before running java
if "$cygwin" || "$msys" ; then
    APP_HOME=$( cygpath --path --mixed "$APP_HOME" )
    CLASSPATH=$( cygpath --path --mixed "$CLASSPATH" )

    JAVACMD=$( cygpath --unix "$JAVACMD" )

    # Now convert the arguments - kludge to limit ourselves to /bin/sh
    for arg do
        if
            case $arg in                                #(
                -*)   false ;;                          # don't mess with options   #(
                /?*)  t=${arg#/} t=/${t%%/*}            # look for metachars in it  #(
                      [ -e "$t" ] ;;                    # @(#)
                *)    false ;;
            esac
        then
            arg=$( cygpath --path --ignore --mixed "$arg" )
        fi
        # Roll the args list around exactly as many times as the number of
        # temporary variables.  (e.g. for 4 temporary variables, roll the args
        # list around 4 times: $1 as the 4th temporary variable, then wrap
        # around the args list so the original arg is at the top.)
        set -- "$@" "$arg"
        shift
    done
fi


# Add default JVM options here. You can also use JAVA_OPTS and GRADLE_OPTS to pass JVM options to this script.
set -- \
    "-Dorg.gradle.appname=$APP_BASE_NAME" \
    -classpath "$CLASSPATH" \
    org.gradle.wrapper.GradleWrapperMain \
    "$@"

# Stop when "xargs" is not available.
if ! command -v xargs >/dev/null 2>&1
then
    die "xargs is not available"
fi

# Use "xargs" to parse quoted args.
#
# With -n1 it outputs one arg per line, with the quotes and backslashes removed.
#
# In Cygwin, some if the arguments will still have shell character escapes applied.  To
# handle this, we use the following general approach:
# - We first apply the necessary escaping to the arg
# - Then pass it back for processing using "xargs -n1" which removes the escaping applied
#   by the previous step.  The final arg is then appended to the list.
#
# For example, "A B" becomes "'A B'" which is then processed by xargs to output "A B" again.
#
# This is necessary because some arguments may contain spaces that we need to preserve.
eval "set -- $(
    printf '%s\n' "$DEFAULT_JVM_OPTS $JAVA_OPTS $GRADLE_OPTS" |
    xargs -n1 |
    sed ' s~[^a-zA-Z0-9/=@_-]~\\&~g; ' |
    tr '\n' ' '
    )" '"$@"'

exec "$JAVACMD" "$@"
