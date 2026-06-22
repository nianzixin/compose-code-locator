@ECHO OFF
@REM
@REM Copyright 2015 the original author or authors.
@REM
@REM Licensed under the Apache License, Version 2.0 (the "License");
@REM you may not use this file except in compliance with the License.
@REM You may obtain a copy of the License at
@REM
@REM      https://www.apache.org/licenses/LICENSE-2.0
@REM
@REM Unless required by applicable law or agreed to in writing, software
@REM distributed under the License is distributed on an "AS IS" BASIS,
@REM WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
@REM See the License for the specific language governing permissions and
@REM limitations under the License.
@REM

IF "%DEBUG%"=="" @ECHO OFF
@REM ##########################################################################
@REM
@REM  Gradle startup script for Windows
@REM
@REM ##########################################################################

@SET DIRNAME=%~dp0
@IF "%DIRNAME%"=="" SET DIRNAME=.
@SET APP_BASE_NAME=%~n0
@SET APP_HOME=%DIRNAME%

@FOR %%i IN ("%APP_HOME%") DO SET APP_HOME=%%~fi

IF "%GRADLE_USER_HOME%"=="" SET GRADLE_USER_HOME=%APP_HOME%\.gradle-user-home
IF "%JAVA_HOME%"=="" SET JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home

@SET DEFAULT_JVM_OPTS="-Xmx64m" "-Xms64m"

@SET JAVA_EXE=java.exe
@%JAVA_EXE% -version >NUL 2>&1
@IF "%ERRORLEVEL%" == "0" GOTO execute

@ECHO.
@ECHO ERROR: JAVA_HOME is not set and no 'java' command could be found in your PATH.
@ECHO.
@ECHO Please set the JAVA_HOME variable in your environment to match the
@ECHO location of your Java installation.

@GOTO fail

:execute
@SET CLASSPATH=%APP_HOME%\gradle\wrapper\gradle-wrapper.jar

"%JAVA_EXE%" %DEFAULT_JVM_OPTS% %JAVA_OPTS% %GRADLE_OPTS% "-Dorg.gradle.appname=%APP_BASE_NAME%" -classpath "%CLASSPATH%" org.gradle.wrapper.GradleWrapperMain %*

:end
@IF "%ERRORLEVEL%"=="0" GOTO mainEnd

:fail
SET EXIT_CODE=%ERRORLEVEL%
IF %EXIT_CODE% EQU 0 SET EXIT_CODE=1
IF NOT ""=="%GRADLE_EXIT_CONSOLE%" EXIT %EXIT_CODE%
EXIT /B %EXIT_CODE%

:mainEnd
IF "%OS%"=="Windows_NT" ENDLOCAL

:omega
