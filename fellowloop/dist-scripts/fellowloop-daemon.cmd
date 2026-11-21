@echo off
rem Runs the Fellowloop daemon on Windows. Needs Java 21+.
set DIR=%~dp0
java %FELLOWLOOP_DAEMON_JAVA_OPTS% -jar "%DIR%fellowloop-daemon.jar" %*
