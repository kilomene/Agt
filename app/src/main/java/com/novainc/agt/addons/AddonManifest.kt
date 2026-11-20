package com.novainc.agt.addons

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject

/**
 * The add-on system's pure rules: what a package manifest is, what
 * an installed add-on record is, and how the registry file reads
 * and writes. No Android, no filesystem: AddonStore owns the disk,
 * this object owns the format, and the unit tests exercise the
 * exact code both install doors depend on.
 *
 * A package is a zip with addon.json at its root:
 *   {"id": "chromium", "name": "Chromium", "version": "1.0.0",
 *    "description": "...", "bin": ["chromium"]}
 * The registry (files/addons/registry.json) is the single source
 * of truth for what is installed: {"addons": [ <record>, ... ]},
 * where a record is the manifest fields plus installedAt and
 * installedBy. Both doors (the admin job and the owner's console
 * tool) read and write this one format.
 */
object AddonManifest {

    data class Manifest(
        val id: String,
        val name: String,
        val version: String,
        val description: String,
        val bin: List<String>
    )

    data class Installed(
        val manifest: Manifest,
        val installedAt: Long,
        val installedBy: String
    )

    /** The outcome of reading a manifest: the manifest, or the
     * refusal reason when manifest is null. */
    class Parsed(val manifest: Manifest?, val error: String?) {
        val ok: Boolean get() = manifest != null
    }

    private val ID_PATTERN = Regex("^[a-z0-9][a-z0-9-]{0,62}$")

    /** Add-on ids are slugs: they name a directory on disk and a
     * line in the registry, so nothing outside lowercase letters,
     * digits, and dashes is allowed, and no leading dash. */
    fun isValidId(id: String): Boolean = ID_PATTERN.matches(id)

    /** A bin entry names one file directly under the add-on's
     * bin/ directory: a bare file name, never a path. */
    fun isValidBinEntry(name: String): Boolean {
        if (name.isEmpty() || name == "." || name == "..") return false
        return name.none { it == '/' || it == '\\' }
    }

    private fun str(obj: JsonObject, key: String): String {
        val el = obj.get(key) ?: return ""
        return if (el.isJsonPrimitive && el.asJsonPrimitive.isString) el.asString else ""
    }

    /**
     * Read and validate one addon.json. Every field the system
     * relies on is checked here, so a package that passes this
     * gate installs the same way through either door.
     */
    fun parseManifest(text: String): Parsed {
        val root = try {
            Gson().fromJson(text, JsonObject::class.java)
        } catch (e: Exception) {
            return Parsed(null, "addon.json is not valid JSON")
        } ?: return Parsed(null, "addon.json is empty")
        val id = str(root, "id")
        if (!isValidId(id)) return Parsed(null, "addon.json has a bad id: '$id'")
        val name = str(root, "name")
        if (name.isBlank()) return Parsed(null, "addon.json has no name")
        val version = str(root, "version")
        if (version.isBlank()) return Parsed(null, "addon.json has no version")
        val description = str(root, "description")
        val bin = mutableListOf<String>()
        val binEl = root.get("bin")
        if (binEl != null && !binEl.isJsonNull) {
            if (!binEl.isJsonArray) return Parsed(null, "addon.json bin is not a list")
            for (el in binEl.asJsonArray) {
                if (!el.isJsonPrimitive || !el.asJsonPrimitive.isString) {
                    return Parsed(null, "addon.json bin holds a non-string entry")
                }
                val entry = el.asString
                if (!isValidBinEntry(entry)) {
                    return Parsed(null, "addon.json bin entry is not a bare file name: '$entry'")
                }
                if (entry !in bin) bin.add(entry)
            }
        }
        return Parsed(Manifest(id, name, version, description, bin), null)
    }

    fun manifestToJson(m: Manifest): JsonObject {
        val obj = JsonObject()
        obj.addProperty("id", m.id)
        obj.addProperty("name", m.name)
        obj.addProperty("version", m.version)
        obj.addProperty("description", m.description)
        val arr = JsonArray()
        for (b in m.bin) arr.add(b)
        obj.add("bin", arr)
        return obj
    }

    fun installedToJson(inst: Installed): JsonObject {
        val obj = manifestToJson(inst.manifest)
        obj.addProperty("installedAt", inst.installedAt)
        obj.addProperty("installedBy", inst.installedBy)
        return obj
    }

    /** The registry file's full text for a set of records. */
    fun registryToJson(list: List<Installed>): String {
        val arr = JsonArray()
        for (inst in list) arr.add(installedToJson(inst))
        val root = JsonObject()
        root.add("addons", arr)
        return Gson().toJson(root)
    }

    /**
     * Read a registry file's text. A missing, empty, or damaged
     * registry reads as no add-ons installed: the registry is a
     * listing, never a lock, and a torn read must not wedge the
     * system. Records whose manifest would not validate are
     * skipped, so one bad record cannot hide the rest.
     */
    fun parseRegistry(text: String): List<Installed> {
        if (text.isBlank()) return emptyList()
        val root = try {
            Gson().fromJson(text, JsonObject::class.java)
        } catch (e: Exception) {
            return emptyList()
        } ?: return emptyList()
        val arr = root.get("addons")?.takeIf { it.isJsonArray }?.asJsonArray
            ?: return emptyList()
        val out = mutableListOf<Installed>()
        for (el in arr) {
            if (!el.isJsonObject) continue
            val parsed = parseManifest(Gson().toJson(el))
            val m = parsed.manifest ?: continue
            val obj = el.asJsonObject
            val at = obj.get("installedAt")
                ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
                ?.asLong ?: 0L
            out.add(Installed(m, at, str(obj, "installedBy")))
        }
        return out
    }
}
