#!/usr/bin/env bash
set -e

echo "========================================================"
echo "  Pdocxles - Native Open-Source Document Viewer Build"
echo "========================================================"
echo ""

OS="$(uname -s)"
ARCH="$(uname -m)"

case "${OS}" in
    Linux*)     PLATFORM="linux";;
    Darwin*)    PLATFORM="mac";;
    *)          PLATFORM="unknown";;
esac

if [ "${PLATFORM}" = "unknown" ]; then
    echo "[ERROR] Unsupported OS: ${OS}"
    exit 1
fi

# 1. JDK Setup
JDK_DIR="${HOME}/.jdk17"
JAVA_BIN="${JDK_DIR}/jdk-17.0.10+7/bin/java"
if [ "${PLATFORM}" = "mac" ] && [ -d "${JDK_DIR}/jdk-17.0.10+7/Contents/Home" ]; then
    JAVA_BIN="${JDK_DIR}/jdk-17.0.10+7/Contents/Home/bin/java"
fi

if [ ! -f "${JAVA_BIN}" ]; then
    echo "[1/4] Downloading OpenJDK 17 for ${PLATFORM} (${ARCH})..."
    mkdir -p "${JDK_DIR}"
    
    if [ "${PLATFORM}" = "linux" ]; then
        if [ "${ARCH}" = "aarch64" ] || [ "${ARCH}" = "arm64" ]; then
            JDK_URL="https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.10%2B7/OpenJDK17U-jdk_aarch64_linux_hotspot_17.0.10_7.tar.gz"
        else
            JDK_URL="https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.10%2B7/OpenJDK17U-jdk_x64_linux_hotspot_17.0.10_7.tar.gz"
        fi
        curl -sL "${JDK_URL}" | tar -xz -C "${JDK_DIR}"
    elif [ "${PLATFORM}" = "mac" ]; then
        if [ "${ARCH}" = "arm64" ] || [ "${ARCH}" = "aarch64" ]; then
            JDK_URL="https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.10%2B7/OpenJDK17U-jdk_aarch64_mac_hotspot_17.0.10_7.tar.gz"
        else
            JDK_URL="https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.10%2B7/OpenJDK17U-jdk_x64_mac_hotspot_17.0.10_7.tar.gz"
        fi
        curl -sL "${JDK_URL}" | tar -xz -C "${JDK_DIR}"
    fi
else
    echo "[1/4] OpenJDK 17 is ready."
fi

if [ -d "${JDK_DIR}/jdk-17.0.10+7/Contents/Home" ]; then
    export JAVA_HOME="${JDK_DIR}/jdk-17.0.10+7/Contents/Home"
else
    export JAVA_HOME="${JDK_DIR}/jdk-17.0.10+7"
fi
export PATH="${JAVA_HOME}/bin:${PATH}"

# 2. Gradle Setup
GRADLE_DIR="${HOME}/.gradle87"
GRADLE_BIN="${GRADLE_DIR}/gradle-8.7/bin/gradle"

if [ ! -f "${GRADLE_BIN}" ]; then
    echo "[2/4] Downloading Gradle 8.7..."
    mkdir -p "${GRADLE_DIR}"
    TMP_ZIP="/tmp/gradle87.zip"
    curl -sL "https://services.gradle.org/distributions/gradle-8.7-bin.zip" -o "${TMP_ZIP}"
    unzip -q "${TMP_ZIP}" -d "${GRADLE_DIR}"
    rm -f "${TMP_ZIP}"
    chmod +x "${GRADLE_BIN}"
else
    echo "[2/4] Gradle 8.7 is ready."
fi

# 3. Android SDK Resolution
FOUND_SDK=""
if [ -n "${ANDROID_HOME}" ] && [ -d "${ANDROID_HOME}/platforms" ]; then
    FOUND_SDK="${ANDROID_HOME}"
elif [ -n "${ANDROID_SDK_ROOT}" ] && [ -d "${ANDROID_SDK_ROOT}/platforms" ]; then
    FOUND_SDK="${ANDROID_SDK_ROOT}"
elif [ "${PLATFORM}" = "mac" ] && [ -d "${HOME}/Library/Android/sdk/platforms" ]; then
    FOUND_SDK="${HOME}/Library/Android/sdk"
elif [ -d "${HOME}/Android/Sdk/platforms" ]; then
    FOUND_SDK="${HOME}/Android/Sdk"
elif [ -d "${HOME}/.android-sdk/platforms" ]; then
    FOUND_SDK="${HOME}/.android-sdk"
fi

AUTO_SDK_DIR="${HOME}/.android-sdk"

if [ -z "${FOUND_SDK}" ]; then
    echo "[3/4] Android SDK not found on system. Setting up Portable Android SDK..."
    FOUND_SDK="${AUTO_SDK_DIR}"
else
    echo "[3/4] Android SDK detected at: ${FOUND_SDK}"
fi

export ANDROID_HOME="${FOUND_SDK}"
export ANDROID_SDK_ROOT="${FOUND_SDK}"

# Auto-accept Android SDK licenses
mkdir -p "${FOUND_SDK}/licenses"
cat << 'EOF' > "${FOUND_SDK}/licenses/android-sdk-license"
89330172541f4551b1178f38e95c3ee68e7d6999
24333f8a63718c1552590efe79888997432559c9
d56f5187479451eabf01fb78af6dfcb131a6481e
EOF

cat << 'EOF' > "${FOUND_SDK}/licenses/android-sdk-preview-license"
84831b9409646a918e30573bab4c9c91346d8abd
EOF

cat << 'EOF' > "${FOUND_SDK}/licenses/intel-android-sysimage-license"
d9588965420b39818571765178210000a30e6649
EOF

# Install SDK components if platform-34 is missing
if [ ! -d "${FOUND_SDK}/platforms/android-34" ]; then
    CMDLINE_DIR="${AUTO_SDK_DIR}/cmdline-tools/latest"
    SDKMANAGER="${CMDLINE_DIR}/bin/sdkmanager"

    if [ ! -f "${SDKMANAGER}" ]; then
        echo "[3/4] Downloading Android Command-Line Tools..."
        mkdir -p "${AUTO_SDK_DIR}"
        TMP_CMDLINE="/tmp/cmdline-tools.zip"
        
        if [ "${PLATFORM}" = "mac" ]; then
            CMDLINE_URL="https://dl.google.com/android/repository/commandlinetools-mac-11076708_latest.zip"
        else
            CMDLINE_URL="https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"
        fi
        
        curl -sL "${CMDLINE_URL}" -o "${TMP_CMDLINE}"
        TMP_EXTRACT="/tmp/cmdline-extract"
        mkdir -p "${TMP_EXTRACT}"
        unzip -q "${TMP_CMDLINE}" -d "${TMP_EXTRACT}"
        rm -f "${TMP_CMDLINE}"

        if [ -d "${TMP_EXTRACT}/cmdline-tools" ]; then
            mkdir -p "${AUTO_SDK_DIR}/cmdline-tools"
            rm -rf "${CMDLINE_DIR}"
            mv "${TMP_EXTRACT}/cmdline-tools" "${CMDLINE_DIR}"
        fi
        rm -rf "${TMP_EXTRACT}"
    fi

    if [ -f "${SDKMANAGER}" ]; then
        chmod +x "${SDKMANAGER}"
        echo "[3/4] Downloading Android Platform 34 and Build-Tools 34.0.0..."
        yes | "${SDKMANAGER}" --sdk_root="${FOUND_SDK}" "platforms;android-34" "build-tools;34.0.0" "platform-tools" > /dev/null 2>&1 || true
    fi
fi

# Write local.properties for Gradle
echo "sdk.dir=${FOUND_SDK}" > local.properties

VERSION_STR=$(date +"%y.%m.%d_%H%M")
DEST_APK="Pdocxles_${VERSION_STR}.apk"

echo "[4/4] Building Release APK (v${VERSION_STR})..."
echo ""

"${GRADLE_BIN}" clean assembleRelease

APK_PATH="app/build/outputs/apk/release/app-release.apk"
if [ ! -f "${APK_PATH}" ]; then
    APK_PATH="app/build/outputs/apk/release/app-release-unsigned.apk"
fi

if [ -f "${APK_PATH}" ]; then
    cp -f "${APK_PATH}" "${DEST_APK}"

    echo "========================================================"
    echo "  BUILD SUCCESSFUL! (Ready to Install)"
    echo "========================================================"
    echo "  Copied to Root: ${DEST_APK}"
    SIZE_KB=$(du -k "${DEST_APK}" | cut -f1)
    echo "  Size: ${SIZE_KB} KB"
    echo "========================================================"
else
    echo "[ERROR] Build failed or APK not found."
    exit 1
fi
