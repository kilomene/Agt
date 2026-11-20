package com.novainc.agt.addons

import android.content.Context
import android.system.Os
import android.system.OsConstants
import com.novainc.agt.linux.NativeLinux
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/**
 * The add-on system's hands: installs, removes, lists, and links
 * add-on packages on disk. The format rules live in AddonManifest;
 * this class owns the filesystem and the network fetch.
 *
 * Layout, all under the app's files directory and deliberately
 * OUTSIDE the bootstrap prefix (files/usr), so add-ons survive a
 * bootstrap reinstall untouched:
 *   files/addons/<id>/       one directory per installed add-on
 *   files/addons/registry.json   the public list of what is installed
 *   files/addons/bin/        shared bin: links to every add-on's
 *                            bin entries, plus the agt-addon tool
 *   files/addons/lib/        shared lib: links to every add-on's
 *                            lib files
 * NativeLinux.environment() puts the shared bin on PATH and the
 * shared lib on LD_LIBRARY_PATH, so the console, deployments, and
 * agent command jobs all reach add-on tools with no extra wiring.
 *
 * Two doors install, and they write the same registry in the same
 * format: the admin job (JobEngine's addon_install, main-signed
 * only, the package pinned by a sha256 in the signed payload) and
 * the owner's console tool (agt-addon, written into the shared bin
 * by ensureConsoleTool; it runs in the owner's own shell, where
 * holding the phone is the authority). Listing is open: any
 * verified agent's addon_list job reads the registry.
 */
class AddonStore(context: Context) {

    private val filesDir: File = context.filesDir
    val addonsDir: File = File(filesDir, "addons")
    val sharedBinDir: File = File(addonsDir, "bin")
    val sharedLibDir: File = File(addonsDir, "lib")
    private val registryFile: File = File(addonsDir, "registry.json")
    private val stagingDir: File = File(addonsDir, ".staging")
    private val lock = Any()

    /** The registry, read fresh from disk on every call: the
     * console tool can change it while the app runs, and a cached
     * copy would answer the owner's question with yesterday's
     * list. */
    fun list(): List<AddonManifest.Installed> = synchronized(lock) { readRegistry() }

    private fun readRegistry(): List<AddonManifest.Installed> {
        val text = try {
            if (registryFile.exists()) registryFile.readText() else ""
        } catch (e: Exception) {
            ""
        }
        return AddonManifest.parseRegistry(text)
    }

    private fun writeRegistry(list: List<AddonManifest.Installed>) {
        addonsDir.mkdirs()
        val text = AddonManifest.registryToJson(list)
        val tmp = File(addonsDir, "registry.json.tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(registryFile)) {
            registryFile.writeText(text)
            tmp.delete()
        }
    }

    /**
     * The admin door's fetch: download the package from [url] to a
     * scratch file, verifying the sha256 pin as it streams, then
     * install. The url is transport only; the signed pin is the
     * trust, so a swapped or corrupted download fails here in the
     * open and nothing is installed.
     */
    fun installFromUrl(
        url: String,
        sha256: String,
        installedBy: String
    ): AddonManifest.Installed {
        addonsDir.mkdirs()
        val tmpZip = File(addonsDir, ".download.zip")
        try {
            download(url, tmpZip, sha256)
            return install(tmpZip, installedBy)
        } finally {
            tmpZip.delete()
        }
    }

    private fun download(url: String, dest: File, sha256: String) {
        val digest = MessageDigest.getInstance("SHA-256")
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 30_000
        conn.readTimeout = 180_000
        conn.instanceFollowRedirects = true
        conn.connect()
        if (conn.responseCode !in 200..299) {
            throw IllegalStateException("download failed: HTTP ${conn.responseCode}")
        }
        var total = 0L
        conn.inputStream.use { input ->
            dest.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    digest.update(buf, 0, n)
                    out.write(buf, 0, n)
                    total += n
                    if (total > MAX_PACKAGE_BYTES) {
                        throw IllegalStateException("add-on package too large")
                    }
                }
            }
        }
        val got = digest.digest().joinToString("") { "%02x".format(it) }
        if (!got.equals(sha256.trim(), ignoreCase = true)) {
            throw IllegalStateException("sha256 mismatch: the package does not match the pin")
        }
    }

    /**
     * Install one package zip: unpack into staging with the same
     * discipline as the bootstrap installer (no entry may escape
     * the add-on directory, SYMLINKS.txt carries the links, bin/
     * and lib/ get their exec bits), validate the manifest, then
     * move the tree into files/addons/<id>/, replacing any older
     * install of the same id, and record it in the registry.
     */
    fun install(zipFile: File, installedBy: String): AddonManifest.Installed {
        synchronized(lock) {
            stagingDir.deleteRecursively()
            stagingDir.mkdirs()
            val symlinks = mutableListOf<Pair<String, String>>()
            try {
                ZipInputStream(zipFile.inputStream().buffered()).use { zip ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        if (entry.name == "SYMLINKS.txt") {
                            val text = zip.readBytes().toString(Charsets.UTF_8)
                            symlinks += NativeLinux.parseSymlinks(text)
                        } else if (entry.isDirectory) {
                            File(stagingDir, entry.name).mkdirs()
                        } else {
                            val target = File(stagingDir, entry.name)
                            check(target.canonicalPath.startsWith(stagingDir.canonicalPath)) {
                                "package entry escapes the add-on directory: ${entry.name}"
                            }
                            target.parentFile?.mkdirs()
                            target.outputStream().use { out ->
                                while (true) {
                                    val n = zip.read(buffer)
                                    if (n < 0) break
                                    out.write(buffer, 0, n)
                                }
                            }
                        }
                        zip.closeEntry()
                    }
                }
            } catch (e: Exception) {
                stagingDir.deleteRecursively()
                throw IllegalStateException("package did not unpack: ${e.message}")
            }
            val manifestFile = File(stagingDir, "addon.json")
            if (!manifestFile.exists()) {
                stagingDir.deleteRecursively()
                throw IllegalStateException("package has no addon.json")
            }
            val parsed = AddonManifest.parseManifest(manifestFile.readText())
            val manifest = parsed.manifest
            if (manifest == null) {
                stagingDir.deleteRecursively()
                throw IllegalStateException(parsed.error ?: "addon.json is invalid")
            }
            for ((linkTarget, linkPath) in symlinks) {
                val link = File(stagingDir, linkPath)
                if (!link.canonicalPath.startsWith(stagingDir.canonicalPath)) continue
                link.parentFile?.mkdirs()
                try { Os.symlink(linkTarget, link.absolutePath) } catch (e: Exception) { }
            }
            stagingDir.walkTopDown().forEach { f ->
                if (f.isFile) {
                    val rel = f.relativeTo(stagingDir).path
                    if (rel.startsWith("bin/") || rel.startsWith("lib/")) {
                        try { Os.chmod(f.absolutePath, 493) } catch (e: Exception) { }
                    }
                }
            }
            val targetDir = File(addonsDir, manifest.id)
            unlinkAddon(manifest.id)
            targetDir.deleteRecursively()
            if (!stagingDir.renameTo(targetDir)) {
                stagingDir.copyRecursively(targetDir, overwrite = true)
                stagingDir.deleteRecursively()
            }
            val installed = AddonManifest.Installed(
                manifest, System.currentTimeMillis(), installedBy
            )
            writeRegistry(readRegistry().filter { it.manifest.id != manifest.id } + installed)
            linkAddon(manifest)
            return installed
        }
    }

    /** Remove one add-on whole: its shared links, its directory,
     * and its registry entry. An id the registry does not know and
     * no directory carries is refused, never silently succeeded. */
    fun remove(id: String): Boolean {
        if (!AddonManifest.isValidId(id)) return false
        synchronized(lock) {
            val records = readRegistry()
            val dir = File(addonsDir, id)
            if (records.none { it.manifest.id == id } && !dir.isDirectory) return false
            unlinkAddon(id)
            dir.deleteRecursively()
            writeRegistry(records.filter { it.manifest.id != id })
            return true
        }
    }

    /**
     * Bring the shared links back into agreement with the
     * registry: links pointing into add-ons the registry no longer
     * lists are dropped, every listed add-on is re-linked, and the
     * console tool is refreshed. Runs on every service start, so a
     * registry changed from the console while the app was down, or
     * links lost to a filesystem event, heal themselves with no
     * human step. A failure here never holds up the host's start.
     */
    fun reconcile() {
        try {
            synchronized(lock) {
                addonsDir.mkdirs()
                sharedBinDir.mkdirs()
                sharedLibDir.mkdirs()
                val records = readRegistry()
                val liveIds = records.map { it.manifest.id }.toSet()
                for (shared in listOf(sharedBinDir, sharedLibDir)) {
                    val entries = shared.listFiles() ?: continue
                    for (f in entries) {
                        if (!isSymlink(f)) continue
                        val target = try { Os.readlink(f.absolutePath) } catch (e: Exception) { continue }
                        val owner = ownerIdOfLink(target)
                        if (owner != null && owner !in liveIds) f.delete()
                    }
                }
                for (inst in records) {
                    if (File(addonsDir, inst.manifest.id).isDirectory) {
                        linkAddon(inst.manifest)
                    }
                }
            }
            ensureConsoleTool()
        } catch (e: Exception) {
            // Reconciliation is a heal pass, not a gate: the next
            // start runs it again.
        }
    }

    private fun ownerIdOfLink(target: String): String? {
        val base = addonsDir.absolutePath + File.separator
        if (!target.startsWith(base)) return null
        val id = target.removePrefix(base).substringBefore(File.separator)
        return if (AddonManifest.isValidId(id)) id else null
    }

    private fun linkAddon(manifest: AddonManifest.Manifest) {
        val dir = File(addonsDir, manifest.id)
        sharedBinDir.mkdirs()
        sharedLibDir.mkdirs()
        unlinkAddon(manifest.id)
        for (name in manifest.bin) {
            val src = File(File(dir, "bin"), name)
            if (src.exists()) linkInto(sharedBinDir, name, src)
        }
        val libs = File(dir, "lib").listFiles() ?: emptyArray()
        for (f in libs) {
            if (f.isFile) linkInto(sharedLibDir, f.name, f)
        }
    }

    private fun linkInto(shared: File, name: String, src: File) {
        val dst = File(shared, name)
        try {
            if (isSymlink(dst)) {
                dst.delete()
            } else if (dst.exists()) {
                // A real file in the shared dir is the owner's or
                // the app's own (the agt-addon tool): the installer
                // never deletes what it did not link.
                return
            }
            Os.symlink(src.absolutePath, dst.absolutePath)
        } catch (e: Exception) { }
    }

    private fun unlinkAddon(id: String) {
        val base = File(addonsDir, id).absolutePath + File.separator
        for (shared in listOf(sharedBinDir, sharedLibDir)) {
            val entries = shared.listFiles() ?: continue
            for (f in entries) {
                if (!isSymlink(f)) continue
                val target = try { Os.readlink(f.absolutePath) } catch (e: Exception) { continue }
                if (target.startsWith(base)) f.delete()
            }
        }
    }

    private fun isSymlink(f: File): Boolean {
        return try {
            val mode = Os.lstat(f.absolutePath).st_mode
            (mode and OsConstants.S_IFMT) == OsConstants.S_IFLNK
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Write the owner's console tool, agt-addon, into the shared
     * bin, where the console's PATH already reaches. The shebang
     * names the prefix's python3 by its full path: Android has no
     * /usr/bin/env (the v1.6.20 lesson), so the tool is written
     * with its interpreter spelled out. The tool is rewritten only
     * when its content has drifted from the shipped body, and it
     * is a real file, never a link, so the link machinery above
     * never touches it.
     */
    fun ensureConsoleTool() {
        try {
            sharedBinDir.mkdirs()
            val tool = File(sharedBinDir, "agt-addon")
            val python = File(filesDir, "usr/bin/python3")
            val content = "#!" + python.absolutePath + "\n" + CONSOLE_TOOL_BODY
            val current = if (tool.exists()) {
                try { tool.readText() } catch (e: Exception) { "" }
            } else {
                ""
            }
            if (current != content) tool.writeText(content)
            try { Os.chmod(tool.absolutePath, 493) } catch (e: Exception) { }
        } catch (e: Exception) { }
    }

    companion object {
        /** The largest package the admin door will fetch: generous
         * for a browser-sized add-on, small enough that a wrong
         * url cannot fill the phone. */
        const val MAX_PACKAGE_BYTES: Long = 1_073_741_824L

        /**
         * The console tool's body (everything after the shebang
         * line, which ensureConsoleTool writes from the real
         * prefix path). It implements the same on-disk format as
         * this class: same registry, same manifest rules, same
         * link layout, so the two doors can never drift apart in
         * what an install means. Kept free of dollar signs and
         * triple quotes so it can live in this raw string.
         */
        const val CONSOLE_TOOL_BODY: String = """# agt-addon console tool v1
# The owner's door to the AGT add-on system: list what is
# installed, install a package, remove one. It runs in the
# owner's own shell as the app uid; holding the phone is the
# authority, so there is no signature here. Agents cannot use
# this tool to install: they have no shell on this phone except
# through signed jobs, and the addon_install job is main-only.
import hashlib
import json
import os
import shutil
import ssl
import sys
import time
import urllib.request
import zipfile

ADDONS = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
REGISTRY = os.path.join(ADDONS, "registry.json")
BINDIR = os.path.join(ADDONS, "bin")
LIBDIR = os.path.join(ADDONS, "lib")
IDCHARS = "abcdefghijklmnopqrstuvwxyz0123456789-"


def valid_id(i):
    if not (1 <= len(i) <= 63):
        return False
    if i[0] not in "abcdefghijklmnopqrstuvwxyz0123456789":
        return False
    return all(c in IDCHARS for c in i)


def valid_bin(n):
    return n not in ("", ".", "..") and "/" not in n and "\\" not in n


def parse_manifest_obj(obj):
    try:
        mid = obj.get("id", "")
        name = obj.get("name", "")
        ver = obj.get("version", "")
        desc = obj.get("description", "")
        binl = obj.get("bin", [])
        if not valid_id(mid) or not name or not ver:
            return None
        if not isinstance(binl, list):
            return None
        if any(not isinstance(b, str) or not valid_bin(b) for b in binl):
            return None
        return {"id": mid, "name": name, "version": ver,
                "description": desc, "bin": list(dict.fromkeys(binl))}
    except Exception:
        return None


def read_registry():
    try:
        with open(REGISTRY, "r", encoding="utf-8") as f:
            data = json.load(f)
        out = []
        for rec in data.get("addons", []):
            m = parse_manifest_obj(rec)
            if m:
                out.append({"manifest": m,
                            "installedAt": rec.get("installedAt", 0),
                            "installedBy": rec.get("installedBy", "")})
        return out
    except Exception:
        return []


def write_registry(records):
    os.makedirs(ADDONS, exist_ok=True)
    root = {"addons": []}
    for r in records:
        rec = dict(r["manifest"])
        rec["installedAt"] = r["installedAt"]
        rec["installedBy"] = r["installedBy"]
        root["addons"].append(rec)
    tmp = REGISTRY + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(root, f, indent=1, sort_keys=True)
    os.replace(tmp, REGISTRY)


def unlink_addon(mid):
    base = os.path.join(ADDONS, mid) + os.sep
    for d in (BINDIR, LIBDIR):
        if os.path.isdir(d):
            for name in os.listdir(d):
                p = os.path.join(d, name)
                if os.path.islink(p) and os.readlink(p).startswith(base):
                    os.remove(p)


def link_addon(m):
    target_dir = os.path.join(ADDONS, m["id"])
    os.makedirs(BINDIR, exist_ok=True)
    os.makedirs(LIBDIR, exist_ok=True)
    unlink_addon(m["id"])
    for b in m["bin"]:
        src = os.path.join(target_dir, "bin", b)
        dst = os.path.join(BINDIR, b)
        if os.path.exists(src):
            if os.path.islink(dst):
                os.remove(dst)
            if not os.path.exists(dst):
                os.symlink(src, dst)
    libdir = os.path.join(target_dir, "lib")
    if os.path.isdir(libdir):
        for name in os.listdir(libdir):
            src = os.path.join(libdir, name)
            dst = os.path.join(LIBDIR, name)
            if os.path.isfile(src):
                if os.path.islink(dst):
                    os.remove(dst)
                if not os.path.exists(dst):
                    os.symlink(src, dst)


def cmd_list():
    records = read_registry()
    if not records:
        print("no add-ons installed")
        return 0
    for r in records:
        m = r["manifest"]
        print(m["id"] + "  " + m["name"] + "  " + m["version"])
        if m["description"]:
            print("    " + m["description"])
    return 0


def cmd_install(url, sha):
    os.makedirs(ADDONS, exist_ok=True)
    ctx = None
    cafile = os.environ.get("SSL_CERT_FILE")
    if cafile and os.path.exists(cafile):
        ctx = ssl.create_default_context(cafile=cafile)
    tmpzip = os.path.join(ADDONS, ".download.zip")
    h = hashlib.sha256()
    try:
        with urllib.request.urlopen(url, timeout=180, context=ctx) as r:
            with open(tmpzip, "wb") as f:
                while True:
                    chunk = r.read(65536)
                    if not chunk:
                        break
                    h.update(chunk)
                    f.write(chunk)
    except Exception as e:
        print("download failed: " + str(e))
        return 1
    if h.hexdigest().lower() != sha.lower():
        os.remove(tmpzip)
        print("sha256 mismatch: got " + h.hexdigest())
        return 1
    staging = os.path.join(ADDONS, ".staging-console")
    shutil.rmtree(staging, ignore_errors=True)
    os.makedirs(staging)
    symlinks = []
    try:
        with zipfile.ZipFile(tmpzip) as z:
            for info in z.infolist():
                name = info.filename
                dest = os.path.normpath(os.path.join(staging, name))
                if not (dest == staging or dest.startswith(staging + os.sep)):
                    print("entry escapes the add-on directory: " + name)
                    shutil.rmtree(staging, ignore_errors=True)
                    os.remove(tmpzip)
                    return 1
                if name == "SYMLINKS.txt":
                    text = z.read(info).decode("utf-8")
                    for line in text.splitlines():
                        line = line.strip()
                        if not line:
                            continue
                        parts = line.split("←")
                        if len(parts) == 2:
                            symlinks.append((parts[0], parts[1]))
                    continue
                if name.endswith("/"):
                    os.makedirs(dest, exist_ok=True)
                    continue
                os.makedirs(os.path.dirname(dest), exist_ok=True)
                with z.open(info) as src, open(dest, "wb") as out:
                    shutil.copyfileobj(src, out)
    except Exception as e:
        print("package did not unpack: " + str(e))
        shutil.rmtree(staging, ignore_errors=True)
        os.remove(tmpzip)
        return 1
    os.remove(tmpzip)
    for target, linkpath in symlinks:
        lp = os.path.normpath(os.path.join(staging, linkpath))
        if lp.startswith(staging + os.sep):
            os.makedirs(os.path.dirname(lp), exist_ok=True)
            if os.path.islink(lp) or os.path.exists(lp):
                os.remove(lp)
            try:
                os.symlink(target, lp)
            except OSError:
                pass
    mpath = os.path.join(staging, "addon.json")
    if not os.path.exists(mpath):
        print("package has no addon.json")
        shutil.rmtree(staging, ignore_errors=True)
        return 1
    with open(mpath, "r", encoding="utf-8") as f:
        m = parse_manifest_obj(json.load(f))
    if not m:
        print("addon.json is invalid")
        shutil.rmtree(staging, ignore_errors=True)
        return 1
    for sub in ("bin", "lib"):
        d = os.path.join(staging, sub)
        if os.path.isdir(d):
            for name in os.listdir(d):
                p = os.path.join(d, name)
                if os.path.isfile(p):
                    os.chmod(p, 0o755)
    target = os.path.join(ADDONS, m["id"])
    unlink_addon(m["id"])
    if os.path.isdir(target):
        shutil.rmtree(target)
    os.rename(staging, target)
    records = [r for r in read_registry() if r["manifest"]["id"] != m["id"]]
    records.append({"manifest": m,
                    "installedAt": int(time.time() * 1000),
                    "installedBy": "console"})
    write_registry(records)
    link_addon(m)
    print("installed " + m["id"] + " " + m["version"] + " (" + m["name"] + ")")
    return 0


def cmd_remove(mid):
    if not valid_id(mid):
        print("bad add-on id")
        return 1
    records = read_registry()
    known = any(r["manifest"]["id"] == mid for r in records)
    if not known and not os.path.isdir(os.path.join(ADDONS, mid)):
        print("no such add-on: " + mid)
        return 1
    unlink_addon(mid)
    shutil.rmtree(os.path.join(ADDONS, mid), ignore_errors=True)
    write_registry([r for r in records if r["manifest"]["id"] != mid])
    print("removed " + mid)
    return 0


def main(argv):
    if len(argv) >= 2 and argv[1] == "list":
        return cmd_list()
    if len(argv) == 4 and argv[1] == "install":
        return cmd_install(argv[2], argv[3])
    if len(argv) == 3 and argv[1] == "remove":
        return cmd_remove(argv[2])
    print("usage: agt-addon list | install <url> <sha256> | remove <id>")
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv))
"""
    }
}
