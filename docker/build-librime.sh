#!/bin/bash
# 编译 librime_jni.so for Android (4 架构)
# 用法: ./build-librime.sh [arch]  留空=全部

set -e

NDK_VERSION=25.2.9519653
SDK_ROOT=/opt/android-sdk
NDK=$SDK_ROOT/ndk/$NDK_VERSION
TC=$NDK/toolchains/llvm/prebuilt/linux-x86_64
BUILD=/build
SRC=$BUILD/src
BLD=$BUILD/build
INSTALL=$BUILD/install

# ============ 克隆所有依赖 ============
clone_sources() {
    echo "=== Cloning sources ==="
    mkdir -p $SRC && cd $SRC

    if [ ! -d "librime" ]; then
        git clone --depth=1 https://github.com/rime/librime.git
    fi

    DEPS="$SRC/librime/deps"
    mkdir -p $DEPS

    declare -A REPOS=(
        ["glog"]="https://github.com/google/glog.git"
        ["leveldb"]="https://github.com/google/leveldb.git"
        ["yaml-cpp"]="https://github.com/jbeder/yaml-cpp.git"
        ["marisa-trie"]="https://github.com/s-yata/marisa-trie.git"
        ["snappy"]="https://github.com/google/snappy.git"
        ["opencc"]="https://github.com/BYVoid/OpenCC.git"
    )

    for dep in "${!REPOS[@]}"; do
        if [ ! -d "$DEPS/$dep" ]; then
            echo "Cloning $dep..."
            git clone --depth=1 "${REPOS[$dep]}" "$DEPS/$dep" 2>&1 | tail -1
        fi
    done

    echo "=== All deps ready ==="
    ls $DEPS/
}

# ============ Boost (cross-compile) ============
build_boost() {
    local ARCH=$1
    local API=$2
    echo "=== Building Boost for $ARCH ==="

    local BOOST_VER=1.84.0
    local DIR="boost_${BOOST_VER//./_}"
    local BDIR=$BLD/boost/$ARCH
    local IDIR=$INSTALL/$ARCH

    mkdir -p $BDIR $IDIR

    cd $SRC
    if [ ! -f "$DIR/bootstrap.sh" ]; then
        echo "Downloading Boost..."
        curl -L -o boost.tar.gz "https://archives.boost.io/release/$BOOST_VER/source/$DIR.tar.gz"
        tar xzf boost.tar.gz
        rm boost.tar.gz
    fi

    cd $DIR

    # user-config.jam with absolute paths using environment variables
    cat > $BDIR-user-config.jam << JAMEOF
using clang : android :
    $TC/bin/${API}-clang++ :
    <archiver>$TC/bin/llvm-ar
    <ranlib>$TC/bin/llvm-ranlib
    <compileflags>-fPIC
    <compileflags>-DANDROID
    <compileflags>-D__ANDROID_API__=21
    <compileflags>-std=c++17
JAMEOF

    if [ ! -f ".bootstrapped" ]; then
        ./bootstrap.sh --with-toolset=clang 2>&1 | tail -1
        touch .bootstrapped
    fi

    ./b2 toolset=clang-android \
        --with-filesystem --with-system --with-regex --with-thread \
        --with-date_time --with-locale --with-iostreams --with-program_options \
        --user-config=$BDIR-user-config.jam \
        --build-dir=$BDIR \
        --prefix=$IDIR \
        variant=release link=static threading=multi \
        runtime-link=static \
        target-os=android abi=aapcs \
        -j$(nproc) \
        install 2>&1 | tail -10

    echo "=== Boost done for $ARCH ==="
}

# ============ 通用 CMake 构建 ============
cmake_build() {
    local NAME=$1
    local SRC_DIR=$2
    local ARCH=$3
    local EXTRA_ARGS=$4

    local BDIR=$BLD/$NAME/$ARCH
    local IDIR=$INSTALL/$ARCH
    mkdir -p $BDIR && cd $BDIR

    cmake $SRC_DIR \
        -DCMAKE_TOOLCHAIN_FILE=$NDK/build/cmake/android.toolchain.cmake \
        -DANDROID_ABI=$ARCH \
        -DANDROID_PLATFORM=android-21 \
        -DCMAKE_BUILD_TYPE=Release \
        -DBUILD_SHARED_LIBS=OFF \
        -DCMAKE_INSTALL_PREFIX=$IDIR \
        -DSNAPPY_BUILD_TESTS=OFF \
        -DSNAPPY_BUILD_BENCHMARKS=OFF \
        -GNinja \
        $EXTRA_ARGS

    ninja -j$(nproc) && ninja install
}

# ============ 单个架构完整编译 ============
build_arch() {
    local ARCH=$1
    local API=$2
    echo ""
    echo "=========================================="
    echo "  Building for $ARCH ($API)"
    echo "=========================================="

    local IDIR=$INSTALL/$ARCH
    local BDIR=$BLD/$ARCH
    mkdir -p $IDIR $BDIR

    # Boost
    build_boost $ARCH $API

    # snappy
    echo "--- snappy ---"
    cmake_build "snappy" "$SRC/librime/deps/snappy" "$ARCH" ""

    # leveldb
    echo "--- leveldb ---"
    local LDB_BDIR=$BLD/leveldb/$ARCH
    local LDB_IDIR=$INSTALL/$ARCH
    mkdir -p $LDB_BDIR && cd $LDB_BDIR
    cmake $SRC/librime/deps/leveldb \
        -DCMAKE_TOOLCHAIN_FILE=$NDK/build/cmake/android.toolchain.cmake \
        -DANDROID_ABI=$ARCH -DANDROID_PLATFORM=android-21 \
        -DCMAKE_BUILD_TYPE=Release -DBUILD_SHARED_LIBS=OFF \
        -DCMAKE_INSTALL_PREFIX=$LDB_IDIR \
        -DLEVELDB_BUILD_TESTS=OFF -DLEVELDB_BUILD_BENCHMARKS=OFF \
        -DHAVE_SNAPPY=ON -GNinja
    ninja -j$(nproc) && ninja install

    # marisa-trie
    echo "--- marisa-trie ---"
    cmake_build "marisa" "$SRC/librime/deps/marisa-trie" "$ARCH" ""

    # glog
    echo "--- glog ---"
    local GLOG_BDIR=$BLD/glog/$ARCH
    mkdir -p $GLOG_BDIR && cd $GLOG_BDIR
    cmake $SRC/librime/deps/glog \
        -DCMAKE_TOOLCHAIN_FILE=$NDK/build/cmake/android.toolchain.cmake \
        -DANDROID_ABI=$ARCH -DANDROID_PLATFORM=android-21 \
        -DCMAKE_BUILD_TYPE=Release -DBUILD_SHARED_LIBS=OFF \
        -DCMAKE_INSTALL_PREFIX=$INSTALL/$ARCH \
        -DWITH_GFLAGS=OFF -DWITH_GTEST=OFF -DWITH_UNWIND=OFF \
        -DCMAKE_C_FLAGS="-ffile-prefix-map=$SRC=." \
        -DCMAKE_CXX_FLAGS="-ffile-prefix-map=$SRC=." \
        -GNinja
    ninja -j$(nproc) && ninja install

    # yaml-cpp
    echo "--- yaml-cpp ---"
    local YB=$BLD/yaml-cpp/$ARCH
    mkdir -p $YB && cd $YB
    cmake $SRC/librime/deps/yaml-cpp \
        -DCMAKE_TOOLCHAIN_FILE=$NDK/build/cmake/android.toolchain.cmake \
        -DANDROID_ABI=$ARCH -DANDROID_PLATFORM=android-21 \
        -DCMAKE_BUILD_TYPE=Release -DBUILD_SHARED_LIBS=OFF \
        -DCMAKE_INSTALL_PREFIX=$INSTALL/$ARCH \
        -DYAML_CPP_BUILD_TOOLS=OFF -DYAML_CPP_BUILD_TESTS=OFF \
        -DYAML_CPP_INSTALL=OFF -GNinja
    ninja -j$(nproc) && ninja install

    # OpenCC
    echo "--- OpenCC ---"
    cmake_build "opencc" "$SRC/librime/deps/opencc" "$ARCH" "-DCMAKE_INSTALL_LIBDIR=lib"

    # librime (static)
    echo "--- librime ---"
    local RIME_BDIR=$BLD/librime/$ARCH
    mkdir -p $RIME_BDIR && cd $RIME_BDIR
    cmake $SRC/librime \
        -DCMAKE_TOOLCHAIN_FILE=$NDK/build/cmake/android.toolchain.cmake \
        -DANDROID_ABI=$ARCH -DANDROID_PLATFORM=android-21 \
        -DCMAKE_BUILD_TYPE=Release -DBUILD_SHARED_LIBS=OFF \
        -DCMAKE_INSTALL_PREFIX=$INSTALL/$ARCH \
        -DBUILD_TESTING=OFF -DENABLE_LOGGING=OFF \
        -DCMAKE_C_FLAGS="-I$INSTALL/$ARCH/include -ffile-prefix-map=$SRC=." \
        -DCMAKE_CXX_FLAGS="-I$INSTALL/$ARCH/include -ffile-prefix-map=$SRC=." \
        -DBOOST_ROOT=$INSTALL/$ARCH \
        -DBoost_NO_SYSTEM_PATHS=ON \
        -DBoost_USE_STATIC_LIBS=ON \
        -DOpencc_LIBRARY=$INSTALL/$ARCH/lib/libopencc.a \
        -DOpencc_INCLUDE_PATH=$INSTALL/$ARCH/include \
        -GNinja
    ninja -j$(nproc) 2>&1 | tail -5
    ninja install

    # rime_jni (shared .so)
    echo "--- rime_jni ---"
    local JNI_BDIR=$BLD/rime_jni/$ARCH
    mkdir -p $JNI_BDIR && cd $JNI_BDIR
    cmake /app/jni/librime_jni \
        -DCMAKE_TOOLCHAIN_FILE=$NDK/build/cmake/android.toolchain.cmake \
        -DANDROID_ABI=$ARCH -DANDROID_PLATFORM=android-21 \
        -DCMAKE_BUILD_TYPE=Release \
        -DBUILD_SHARED_LIBS=ON \
        -DCMAKE_INSTALL_PREFIX=$INSTALL/$ARCH \
        -Drime_DIR=$INSTALL/$ARCH/lib/cmake/rime \
        -DOpencc_LIBRARY=$INSTALL/$ARCH/lib/libopencc.a \
        -DOpencc_INCLUDE_PATH=$INSTALL/$ARCH/include \
        -DCMAKE_C_FLAGS="-I$INSTALL/$ARCH/include -I$SRC/librime/include" \
        -DCMAKE_CXX_FLAGS="-I$INSTALL/$ARCH/include -I$SRC/librime/include" \
        -GNinja
    ninja -j$(nproc) 2>&1 | tail -10

    # 收集 .so
    mkdir -p /output/$ARCH
    find $JNI_BDIR -name "*.so" -exec cp {} /output/$ARCH/ \;
    find $INSTALL/$ARCH/lib -name "*.so" -exec cp {} /output/$ARCH/ \;

    echo ""
    echo "=== $ARCH DONE ==="
    ls -lh /output/$ARCH/ 2>/dev/null || echo "No .so yet"
}

# ============ Main ============
mkdir -p $SRC $BLD $INSTALL /output

cd $BUILD

# 克隆源码
clone_sources

# 编译架构
if [ -n "$1" ]; then
    case $1 in
        arm64)   build_arch arm64-v8a aarch64-linux-android21 ;;
        armeabi) build_arch armeabi-v7a armv7a-linux-androideabi21 ;;
        x64)     build_arch x86_64 x86_64-linux-android21 ;;
        x86)     build_arch x86 i686-linux-android21 ;;
        *)       echo "Unknown: $1"; exit 1 ;;
    esac
else
    build_arch arm64-v8a aarch64-linux-android21
    build_arch armeabi-v7a armv7a-linux-androideabi21
    build_arch x86_64 x86_64-linux-android21
    build_arch x86 i686-linux-android21
fi

echo ""
echo "=========================================="
echo "  ALL DONE!"
echo "=========================================="
find /output -name "*.so" -exec ls -lh {} \;
