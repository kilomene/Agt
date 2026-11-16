#!/bin/bash
NB="$HOME/workspace/Agt-tools/native-build"
NDK="$HOME/workspace/android-sdk/ndk/28.2.13676358/toolchains/llvm/prebuilt/linux-x86_64/bin"
STAGE="$NB/stage"
PREFIX="/data/data/com.novainc.agt/files/usr"
cd "$NB/src/bash-5.2.37"
# The system profile a login shell reads: on this system there is no
# writable /etc, so bash must read the profile inside the app's own
# prefix, the path it will actually have on the phone.
# pathnames.h is generated from pathnames.h.in by configure; patch the
# source so the generated header carries the prefix path.
sed -i 's|#define SYS_PROFILE "/etc/profile"|#define SYS_PROFILE "/data/data/com.novainc.agt/files/usr/etc/profile"|' pathnames.h.in
grep -n "SYS_PROFILE" pathnames.h.in | head -2
export CC="$NDK/aarch64-linux-android26-clang"
export AR="$NDK/llvm-ar"
export RANLIB="$NDK/llvm-ranlib"
export STRIP="$NDK/llvm-strip"
export CPPFLAGS="-I$STAGE/usr/include"
export LDFLAGS="-L$STAGE/usr/lib"
./configure --host=aarch64-linux-android --prefix="$PREFIX" \
  --without-bash-malloc --disable-nls --disable-rpath \
  > "$NB/bash-configure.log" 2>&1
echo "configure exit=$?"
make -j4 > "$NB/bash-make.log" 2>&1
echo "make exit=$?"
ls -la bash 2>/dev/null
