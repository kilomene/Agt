#!/usr/bin/env python3
"""Dry-run of NativeLinux.install against the real bootstrap zip,
on this VM: same extraction order, same SYMLINKS.txt handling, same
chmod rule (every file under bin/ and lib/ gets 0755). Execution of
the binaries is NOT part of this: bionic binaries cannot run here.
"""
import os, zipfile, sys, subprocess

NB = os.path.expanduser("~/workspace/Agt-tools/native-build")
MOCK = os.path.join(NB, "mock-prefix")
subprocess.run(["rm", "-rf", MOCK], check=True)
staging = MOCK + "-staging"
os.makedirs(staging)

links = []
with zipfile.ZipFile(os.path.join(NB, "bootstrap-aarch64.zip")) as z:
    names = z.namelist()
    assert "SYMLINKS.txt" in names, "no SYMLINKS.txt: hard error in the app too"
    for name in names:
        dest = os.path.join(staging, name)
        canon = os.path.realpath(dest)
        assert canon.startswith(os.path.realpath(staging)), f"traversal: {name}"
        if name.endswith("/"):
            os.makedirs(dest, exist_ok=True)
            continue
        os.makedirs(os.path.dirname(dest), exist_ok=True)
        data = z.read(name)
        if name == "SYMLINKS.txt":
            for line in data.decode().splitlines():
                t = line.strip()
                if not t:
                    continue
                parts = t.split("←")
                if len(parts) != 2 or not parts[0] or not parts[1]:
                    continue
                links.append((parts[0], parts[1]))
            continue
        with open(dest, "wb") as f:
            f.write(data)

for target, rel in links:
    p = os.path.join(staging, rel)
    os.makedirs(os.path.dirname(p), exist_ok=True)
    if os.path.lexists(p):
        os.remove(p)
    os.symlink(target, p)

for sub in ("bin", "lib"):
    d = os.path.join(staging, sub)
    for root, _, files in os.walk(d):
        for fn in files:
            fp = os.path.join(root, fn)
            if not os.path.islink(fp):
                os.chmod(fp, 0o755)

os.rename(staging, MOCK)

# Verification
fails = []
def chk(cond, msg):
    print(("OK  " if cond else "FAIL") + " " + msg)
    if not cond:
        fails.append(msg)

for f in ("bin/bash", "bin/busybox", "lib/libncurses.so.6.4", "etc/profile"):
    chk(os.path.isfile(os.path.join(MOCK, f)) and not os.path.islink(os.path.join(MOCK, f)), f"{f} is a regular file")
chk(os.access(os.path.join(MOCK, "bin/bash"), os.X_OK), "bin/bash is executable")
chk(os.access(os.path.join(MOCK, "bin/busybox"), os.X_OK), "bin/busybox is executable")
broken = [rel for _, rel in links if not os.path.exists(os.path.join(MOCK, rel))]
chk(not broken, f"all {len(links)} symlinks resolve (broken: {broken[:5]})")
chk(os.path.realpath(os.path.join(MOCK, "bin/sh")).endswith("bin/bash"), "bin/sh resolves to bash")
chk(os.path.realpath(os.path.join(MOCK, "lib/libncurses.so")).endswith("libncurses.so.6.4"), "libncurses.so resolves to .so.6.4")

# Source applet set vs shipped link set
src = set()
bb = os.path.join(NB, "bb-root")
for root, _, files in os.walk(bb):
    for fn in files:
        if os.path.islink(os.path.join(root, fn)):
            src.add(fn)
shipped = {os.path.basename(rel) for _, rel in links if rel.startswith("bin/")}
# Intentional deltas: linuxrc (busybox's init link) is not shipped;
# bin/sh is shipped but points at bash, not busybox.
chk(src - {"linuxrc"} == shipped,
    f"applet sets match exactly (src {len(src)}, shipped {len(shipped)}, diff {src ^ shipped})")
out = subprocess.run(["file", os.path.join(MOCK, "bin/bash"), os.path.join(MOCK, "bin/busybox"),
                      os.path.join(MOCK, "lib/libncurses.so.6.4")], capture_output=True, text=True).stdout
print(out.strip())
sys.exit(1 if fails else 0)
