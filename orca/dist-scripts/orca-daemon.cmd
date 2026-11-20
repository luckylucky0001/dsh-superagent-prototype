@echo off
rem Runs the Orca daemon on Windows. Needs Java 21+.
set DIR=%~dp0
java %ORCA_DAEMON_JAVA_OPTS% -jar "%DIR%orca-daemon.jar" %*
