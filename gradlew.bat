@rem Gradle wrapper launcher for Windows
@if "%DEBUG%"=="" @echo off
@setlocal

set "APP_HOME=%~dp0"
set "APP_BASE_NAME=%~n0"
set "CLASSPATH=%APP_HOME%gradle\wrapper\gradle-wrapper.jar"

if defined JAVA_HOME goto findJavaFromJavaHome

set "JAVA_EXE=java.exe"
%JAVA_EXE% -version >NUL 2>&1
if %ERRORLEVEL% equ 0 goto execute

echo.
echo ERROR: JAVA_HOME is not set and java was not found on PATH.
goto fail

:findJavaFromJavaHome
set JAVA_HOME=%JAVA_HOME:"=%
set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"

if exist "%JAVA_EXE%" goto execute

echo.
echo ERROR: JAVA_HOME points to an invalid Java installation: %JAVA_HOME%
goto fail

:execute
"%JAVA_EXE%" -Xmx64m -Xms64m %JAVA_OPTS% %GRADLE_OPTS% "-Dorg.gradle.appname=%APP_BASE_NAME%" -classpath "%CLASSPATH%" org.gradle.wrapper.GradleWrapperMain %*

if %ERRORLEVEL% equ 0 goto end

:fail
set EXIT_CODE=%ERRORLEVEL%
if %EXIT_CODE% equ 0 set EXIT_CODE=1
exit /b %EXIT_CODE%

:end
@endlocal
