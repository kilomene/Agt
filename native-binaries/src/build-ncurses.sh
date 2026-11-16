#!/bin/bash
set -e
NB="$HOME/workspace/Agt-tools/native-build"
NDK="$HOME/workspace/android-sdk/ndk/28.2.13676358/toolchains/llvm/prebuilt/linux-x86_64/bin"
STAGE="$NB/stage"
export CC="$NDK/aarch64-linux-android26-clang"
export AR="$NDK/llvm-ar"
export RANLIB="$NDK/llvm-ranlib"
export STRIP="$NDK/llvm-strip"
export BUILD_CC=gcc
cd "$NB/src/ncurses-6.4"
./configure --host=aarch64-linux-android --prefix="$STAGE/usr" \
  --with-shared --without-debug --without-ada --without-cxx-binding \
  --without-manpages --without-tests --disable-stripping \
  --with-fallbacks=xterm-256color,xterm,screen,linux,vt100 \
  --enable-widec=no > "$NB/ncurses-configure.log" 2>&1
make -j4 > "$NB/ncurses-make.log" 2>&1
make install > "$NB/ncurses-install.log" 2>&1
echo NCURSES_DONE
ls -la "$STAGE/usr/lib/" | grep -i curses
