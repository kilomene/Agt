#!/usr/bin/env python3
"""Assemble the AGT native bootstrap zip (agt-bootstrap-v1).

Layout, relative to the app prefix (files/usr), Termux-style:
  bin/bash            the shell (bionic, AGT prefix compiled in)
  bin/busybox         the multi-call toolbox binary
  lib/libncurses.so.6.4  bash's readline library
  etc/profile         read by login shells (path compiled into bash)
  tmp/                the prefix tmpdir
  SYMLINKS.txt        every link a zip cannot store, in the Termux
                      format "target<arrow U+2190>path-from-prefix":
                      all busybox applet names -> busybox,
                      bin/sh -> bash, the libncurses soname links.
The app's NativeLinux.install extracts files, then recreates the
links from SYMLINKS.txt, then chmods bin/ and lib/.
"""
import os, zipfile, hashlib, subprocess, sys

NB = os.path.expanduser("~/workspace/Agt-tools/native-build")
NDK_STRIP = os.path.expanduser(
    "~/workspace/android-sdk/ndk/28.2.13676358/toolchains/llvm/"
    "prebuilt/linux-x86_64/bin/llvm-strip")
OUT = os.path.join(NB, "bootstrap-aarch64.zip")

# Strip the ncurses lib (the stage copy keeps its symbols for
# future link work; the shipped copy is stripped).
ncurses_src = os.path.join(NB, "stage/usr/lib/libncurses.so.6.4")
ncurses_ship = os.path.join(NB, "libncurses-ship.so.6.4")
subprocess.run(["cp", ncurses_src, ncurses_ship], check=True)
subprocess.run([NDK_STRIP, ncurses_ship], check=True)

links = {}  # link path (from prefix) -> target
bb_root = os.path.join(NB, "bb-root")
for sub in ("bin", "sbin", "usr/bin", "usr/sbin"):
    d = os.path.join(bb_root, sub)
    if not os.path.isdir(d):
        continue
    for name in sorted(os.listdir(d)):
        p = os.path.join(d, name)
        if os.path.islink(p):
            assert name not in [os.path.basename(k) for k in links], f"dup applet {name}"
            links[f"bin/{name}"] = "busybox"
links["bin/sh"] = "bash"
links["lib/libncurses.so.6"] = "libncurses.so.6.4"
links["lib/libncurses.so"] = "libncurses.so.6"

profile = """# AGT native Linux: the system profile login shells read.
# The path below is compiled into this bash; the app also exports
# the same environment before the shell starts.
export PATH="$PREFIX/bin:/system/bin:/system/xbin"
export TMPDIR="$PREFIX/tmp"
"""

symlinks_txt = "".join(f"{target}←{path}\n" for path, target in sorted(links.items()))

with zipfile.ZipFile(OUT, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as z:
    z.write(os.path.join(NB, "bash-dest/data/data/com.novainc.agt/files/usr/bin/bash"), "bin/bash")
    z.write(os.path.join(bb_root, "bin/busybox"), "bin/busybox")
    z.write(ncurses_ship, "lib/libncurses.so.6.4")
    z.writestr("etc/profile", profile)
    z.writestr("tmp/", "")
    z.writestr("SYMLINKS.txt", symlinks_txt)

h = hashlib.sha256(open(OUT, "rb").read()).hexdigest()
size = os.path.getsize(OUT)
print(f"zip={OUT}")
print(f"size={size} sha256={h}")
print(f"applet+lib links={len(links)}")
