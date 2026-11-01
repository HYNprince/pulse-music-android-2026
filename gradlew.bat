@echo off
cd /d "%~dp0"
if not exist gradle\wrapper\gradle-wrapper.jar (
  echo Downloading official Gradle wrapper jar...
  powershell -NoProfile -Command "Invoke-WebRequest -Uri 'https://raw.githubusercontent.com/gradle/gradle/v8.13.0/gradle/wrapper/gradle-wrapper.jar' -OutFile 'gradle/wrapper/gradle-wrapper.jar'"
)
java -cp gradle\wrapper\gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain %*
