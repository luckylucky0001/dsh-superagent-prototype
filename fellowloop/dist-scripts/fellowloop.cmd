@echo off
rem The fellowloop command-line client on Windows. Needs Java 21+.
set DIR=%~dp0
java %FELLOWLOOP_CLI_JAVA_OPTS% -jar "%DIR%fellowloop-cli.jar" %*
