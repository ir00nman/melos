@echo off
rem MSI-установщик Melos собирается только на Windows (jpackage из JDK 21 + WiX Toolset 3.x).
rem
rem Что нужно один раз:
rem   1. JDK 21 (Temurin): https://adoptium.net  -- и переменная JAVA_HOME на него
rem   2. WiX Toolset 3.14: https://wixtoolset.org (jpackage вызывает candle/light для MSI)
rem   3. VLC ставить не обязательно для сборки, но для звука у пользователя нужен VLC 3 x64.
rem
rem Результат: app\build\compose\binaries\main\msi\Melos-1.0.0.msi
rem Настройки установщика (ярлык, выбор папки, иконка, upgradeUuid) -- в app\build.gradle.kts, nativeDistributions.

setlocal
cd /d "%~dp0..\.."
if "%JAVA_HOME%"=="" (
  echo JAVA_HOME не задана - укажите JDK 21, например: set JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-21
  exit /b 1
)
call gradlew.bat :app:packageMsi -Ptarget=windows %*
endlocal
