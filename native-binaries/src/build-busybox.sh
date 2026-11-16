#!/bin/sh
# Build busybox 1.36.1 for Android (bionic), aarch64, with AGT's
# prefix compiled in as its install root. This script records the
# exact recipe that produced the busybox inside agt-bootstrap-v1
# (the build itself was driven interactively; every step below is
# from that session's logs, in order).
#
# Applets disabled, and why: bionic has no getpwnam-style shadow
# helpers, no /proc-based init, and no desktop console tooling an
# app can use; the disabled set is the login/init/console/network-
# admin family plus applets whose sources need glibc-only headers
# or libraries (nslookup's -lresolv and _res, hostid, su, tc...).
# Everything else in defconfig is compiled in: the install tree carries 375 applet links.
set -e
NDK=${NDK:-$HOME/workspace/android-sdk/ndk/28.2.13676358}
LLVM=$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin
CC=$LLVM/aarch64-linux-android26-clang
export EXTRA_CFLAGS="-Wno-implicit-function-declaration -Wno-int-conversion"
PREFIX=/data/data/com.novainc.agt/files/usr
SRC=$(dirname "$0")/src/busybox-1.36.1
cd "$SRC"

make defconfig CC="$CC" HOSTCC=gcc
sed -i "s|^CONFIG_PREFIX=.*|CONFIG_PREFIX=\"$PREFIX\"|" .config
for opt in LOADFONT SETFONT HOSTID SU CONSPY IFCONFIG TC IPCRM IPCS \
           SWAPON SWAPOFF NSLOOKUP FEATURE_NSLOOKUP_BIG ETHER_WAKE \
           LOGNAME SEEDRNG HUSH HALT POWEROFF REBOOT RUNLEVEL SYNC \
           LOGIN GETTY SULOGIN MKFS_MINIX FSCK_MINIX TELNETD; do
  sed -i "s|^CONFIG_${opt}=y|# CONFIG_${opt} is not set|" .config
done
make oldconfig CC="$CC" HOSTCC=gcc </dev/null

# Bionic's IPv6 header guard is _UAPI_IPV6_H (linux/ipv6.h), not
# glibc's _LINUX_IN6_H; the stock guard leaves struct in6_ifreq
# undefined against bionic headers.
sed -i 's|# if !defined(_LINUX_IN6_H)|# if !defined(_LINUX_IN6_H) \&\& !defined(_UAPI_IPV6_H)|' \
  networking/interface.c networking/ifconfig.c

# updwtmpx is glibc; bionic has none. libbb/agt_compat.c provides
# it with glibc's append-the-record semantics (used by busybox's
# login bookkeeping paths that remain compiled in).
grep -q "agt_compat" libbb/Kbuild.src || \
  sed -i 's|^lib-y += xatonum.o.*|&\nlib-y += agt_compat.o|' libbb/Kbuild.src

make -j4 CC="$CC" HOSTCC=gcc STRIP="$LLVM/llvm-strip"
# Install into a scratch root (NOT the compiled-in prefix): the
# applet links are collected from here by make-bootstrap.py.
make install CONFIG_PREFIX="$(pwd)/../../bb-root" \
  CC="$CC" HOSTCC=gcc STRIP="$LLVM/llvm-strip"
echo "busybox built and installed into bb-root"
