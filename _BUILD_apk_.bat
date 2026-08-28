@echo off
setlocal enabledelayedexpansion
title Building Pdocxles APK

echo ========================================================
echo   Pdocxles - Native Open-Source Document Viewer Build
echo ========================================================
echo.

:: 1. OpenJDK 17 Setup
set "JDK_DIR=%USERPROFILE%\.jdk17"
set "JAVA_EXE=%JDK_DIR%\jdk-17.0.10+7\bin\java.exe"

if not exist "%JAVA_EXE%" (
    if not exist "TEMP" md TEMP
    echo [1/4] Downloading OpenJDK 17...
    powershell -Command "[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12; Invoke-WebRequest -Uri 'https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.10%%2B7/OpenJDK17U-jdk_x64_windows_hotspot_17.0.10_7.zip' -OutFile 'TEMP\jdk17.zip'"
    echo [1/4] Extracting OpenJDK 17...
    powershell -Command "Expand-Archive -Path 'TEMP\jdk17.zip' -DestinationPath '%JDK_DIR%' -Force; Remove-Item 'TEMP\jdk17.zip' -ErrorAction SilentlyContinue"
    if exist "TEMP" rd /s /q TEMP
) else (
    echo [1/4] OpenJDK 17 is ready.
)

set "JAVA_HOME=%JDK_DIR%\jdk-17.0.10+7"
set "PATH=%JAVA_HOME%\bin;%PATH%"

:: 2. Gradle 8.7 Setup
set "GRADLE_DIR=%USERPROFILE%\.gradle87"
set "GRADLE_BAT=%GRADLE_DIR%\gradle-8.7\bin\gradle.bat"

if not exist "%GRADLE_BAT%" (
    if not exist "TEMP" md TEMP
    echo [2/4] Downloading Gradle 8.7...
    powershell -Command "[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12; Invoke-WebRequest -Uri 'https://services.gradle.org/distributions/gradle-8.7-bin.zip' -OutFile 'TEMP\gradle87.zip'"
    echo [2/4] Extracting Gradle 8.7...
    powershell -Command "Expand-Archive -Path 'TEMP\gradle87.zip' -DestinationPath '%GRADLE_DIR%' -Force; Remove-Item 'TEMP\gradle87.zip' -ErrorAction SilentlyContinue"
    if exist "TEMP" rd /s /q TEMP
) else (
    echo [2/4] Gradle 8.7 is ready.
)

:: 3. Android SDK Detection
set "FOUND_SDK="

if defined ANDROID_HOME if exist "%ANDROID_HOME%\platforms" set "FOUND_SDK=%ANDROID_HOME%"
if not defined FOUND_SDK if defined ANDROID_SDK_ROOT if exist "%ANDROID_SDK_ROOT%\platforms" set "FOUND_SDK=%ANDROID_SDK_ROOT%"
if not defined FOUND_SDK if exist "%LOCALAPPDATA%\Android\Sdk\platforms" set "FOUND_SDK=%LOCALAPPDATA%\Android\Sdk"
if not defined FOUND_SDK if exist "%USERPROFILE%\AppData\Local\Android\Sdk\platforms" set "FOUND_SDK=%USERPROFILE%\AppData\Local\Android\Sdk"
if not defined FOUND_SDK if exist "C:\Android\Sdk\platforms" set "FOUND_SDK=C:\Android\Sdk"
if not defined FOUND_SDK if exist "C:\Android\sdk\platforms" set "FOUND_SDK=C:\Android\sdk"
if not defined FOUND_SDK if exist "D:\Android\Sdk\platforms" set "FOUND_SDK=D:\Android\Sdk"
if not defined FOUND_SDK if exist "%PROGRAMFILES%\Android\Android Studio\sdk\platforms" set "FOUND_SDK=%PROGRAMFILES%\Android\Android Studio\sdk"
if not defined FOUND_SDK if exist "%USERPROFILE%\.android-sdk\platforms" set "FOUND_SDK=%USERPROFILE%\.android-sdk"

set "AUTO_SDK_DIR=%USERPROFILE%\.android-sdk"

if not defined FOUND_SDK (
    echo [3/4] Android SDK not found on system. Setting up Portable Android SDK...
    set "FOUND_SDK=!AUTO_SDK_DIR!"
) else (
    echo [3/4] Android SDK detected at: !FOUND_SDK!
)

set "ANDROID_HOME=!FOUND_SDK!"
set "ANDROID_SDK_ROOT=!FOUND_SDK!"

:: Auto-accept Android SDK licenses
if not exist "!FOUND_SDK!\licenses" md "!FOUND_SDK!\licenses"
powershell -Command "Set-Content -Path '!FOUND_SDK!\licenses\android-sdk-license' -Value '89330172541f4551b1178f38e95c3ee68e7d6999', '24333f8a63718c1552590efe79888997432559c9', 'd56f5187479451eabf01fb78af6dfcb131a6481e'" >nul 2>&1
powershell -Command "Set-Content -Path '!FOUND_SDK!\licenses\android-sdk-preview-license' -Value '84831b9409646a918e30573bab4c9c91346d8abd'" >nul 2>&1
powershell -Command "Set-Content -Path '!FOUND_SDK!\licenses\intel-android-sysimage-license' -Value 'd9588965420b39818571765178210000a30e6649'" >nul 2>&1

:: Install SDK components if platform-34 is missing
if not exist "!FOUND_SDK!\platforms\android-34" (
    set "CMDLINE_TOOLS_ZIP=!AUTO_SDK_DIR!\cmdline-tools.zip"
    set "CMDLINE_DIR=!AUTO_SDK_DIR!\cmdline-tools\latest"
    set "SDKMANAGER=!CMDLINE_DIR!\bin\sdkmanager.bat"

    if not exist "!SDKMANAGER!" (
        echo [3/4] Downloading Android Command-Line Tools...
        if not exist "!AUTO_SDK_DIR!" md "!AUTO_SDK_DIR!"
        powershell -Command "[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12; Invoke-WebRequest -Uri 'https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip' -OutFile '!CMDLINE_TOOLS_ZIP!'"
        
        echo [3/4] Extracting Command-Line Tools...
        powershell -Command "Expand-Archive -Path '!CMDLINE_TOOLS_ZIP!' -DestinationPath '!AUTO_SDK_DIR!\cmdline-tools-tmp' -Force"
        if exist "!AUTO_SDK_DIR!\cmdline-tools-tmp\cmdline-tools" (
            if exist "!CMDLINE_DIR!" rd /s /q "!CMDLINE_DIR!"
            if not exist "!AUTO_SDK_DIR!\cmdline-tools" md "!AUTO_SDK_DIR!\cmdline-tools"
            move "!AUTO_SDK_DIR!\cmdline-tools-tmp\cmdline-tools" "!CMDLINE_DIR!" >nul 2>&1
        )
        if exist "!AUTO_SDK_DIR!\cmdline-tools-tmp" rd /s /q "!AUTO_SDK_DIR!\cmdline-tools-tmp"
        if exist "!CMDLINE_TOOLS_ZIP!" del /f /q "!CMDLINE_TOOLS_ZIP!"
    )

    if exist "!SDKMANAGER!" (
        echo [3/4] Downloading Android Platform 34 and Build-Tools 34.0.0...
        (for /L %%i in (1,1,20) do @echo y) | "!SDKMANAGER!" --sdk_root="!FOUND_SDK!" "platforms;android-34" "build-tools;34.0.0" "platform-tools" >nul 2>&1
    )
)

:: Write local.properties for Gradle (escaping backslashes)
set "ESCAPED_SDK=!FOUND_SDK:\=/!"
echo sdk.dir=!ESCAPED_SDK!> local.properties

for /f "tokens=*" %%V in ('powershell -Command "Get-Date -Format 'yy.MM.dd_HHmm'"') do set "VERSION_STR=%%V"
set "ROOT_APK=Pdocxles_!VERSION_STR!.apk"

echo [4/4] Building Release APK (v!VERSION_STR!)...
echo.

call "%GRADLE_BAT%" clean assembleRelease

echo.

if exist "app\build\outputs\apk\release\app-release.apk" (
    copy /y "app\build\outputs\apk\release\app-release.apk" "!ROOT_APK!" >nul
    echo ========================================================
    echo   BUILD SUCCESSFUL - Signed and Ready to Install
    echo ========================================================
    echo   Copied to Root: !ROOT_APK!
    powershell -Command "$size = [math]::Round((Get-Item '!ROOT_APK!').Length / 1KB); Write-Host '  Size:' $size 'KB'"
    echo ========================================================
    goto END
)

if exist "app\build\outputs\apk\release\app-release-unsigned.apk" (
    copy /y "app\build\outputs\apk\release\app-release-unsigned.apk" "!ROOT_APK!" >nul
    echo ========================================================
    echo   BUILD SUCCESSFUL
    echo ========================================================
    echo   Copied to Root: !ROOT_APK!
    powershell -Command "$size = [math]::Round((Get-Item '!ROOT_APK!').Length / 1KB); Write-Host '  Size:' $size 'KB'"
    echo ========================================================
    goto END
)

echo [ERROR] Build failed or APK not found.

:END
