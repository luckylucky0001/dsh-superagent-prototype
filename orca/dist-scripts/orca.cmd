@echo off
rem The orca command-line client on Windows. Needs Java 21+.
set DIR=%~dp0
java %ORCA_CLI_JAVA_OPTS% -jar "%DIR%orca-cli.jar" %*
