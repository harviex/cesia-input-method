FROM ubuntu:22.04

ENV DEBIAN_FRONTEND=noninteractive \
    NDK_VERSION=25.2.9519653 \
    SDK_ROOT=/opt/android-sdk \
    JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64

# 安装基础工具 + JDK 17
RUN apt-get update && apt-get install -y --no-install-recommends \
    openjdk-17-jdk-headless \
    cmake \
    ninja-build \
    pkg-config \
    git \
    curl \
    unzip \
    python3 \
    gcc \
    g++ \
    make \
    file \
    && rm -rf /var/lib/apt/lists/*

# 安装 Android SDK cmdline-tools + NDK
RUN mkdir -p $SDK_ROOT/cmdline-tools && \
    curl -sL "https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip" -o /tmp/cmdtools.zip && \
    unzip -q /tmp/cmdtools.zip -d /tmp/ && \
    mv /tmp/cmdline-tools $SDK_ROOT/cmdline-tools/latest && \
    rm -f /tmp/cmdtools.zip && \
    export PATH=$JAVA_HOME/bin:$SDK_ROOT/cmdline-tools/latest/bin:$PATH && \
    yes | sdkmanager --licenses > /dev/null 2>&1 || true && \
    sdkmanager --install "ndk;${NDK_VERSION}" "platforms;android-34" > /dev/null 2>&1

WORKDIR /build

# 默认命令：交互式 shell
CMD ["/bin/bash"]
