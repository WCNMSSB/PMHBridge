package com.pmh.bridge

import android.util.Log
import org.json.JSONObject
import java.io.DataOutputStream
import java.io.FileInputStream

/**
 * Rule file client: /data/local/tmp/pmh_rules.json
 *
 * The LSPosed module reads the same file. Reads need no root, writes go through
 * `su -c cat > file`, then the write is verified by reading it back.
 */
object RulesIO {
    const val PATH = "/data/local/tmp/pmh_rules.json"

    private const val TAG = "PmhRules"

    fun load(): JSONObject = load(null)

    fun load(@Suppress("UNUSED_PARAMETER") context: android.content.Context?): JSONObject {
        return try {
            FileInputStream(PATH).use { input ->
                val bytes = input.readBytes()
                if (bytes.isEmpty()) JSONObject() else JSONObject(String(bytes, Charsets.UTF_8))
            }
        } catch (t: Throwable) {
            Log.i(TAG, "load failed: $t")
            JSONObject()
        }
    }

    fun save(root: JSONObject): Boolean = save(null, root)

    fun save(@Suppress("UNUSED_PARAMETER") context: android.content.Context?, root: JSONObject): Boolean {
        val json = try {
            root.toString(2)
        } catch (t: Throwable) {
            root.toString()
        }
        // Direct write first: the rule file is mode 666, so no root is needed and the
        // app works on a non-rooted headset too.
        try {
            java.io.FileOutputStream(PATH).use { out ->
                out.write(json.toByteArray(Charsets.UTF_8))
            }
            if (readRaw()?.let { JSONObject(it).toString() } == JSONObject(json).toString()) {
                return true
            }
        } catch (t: Throwable) {
            Log.i(TAG, "direct save failed: $t")
        }

        for (su in listOf("/product/bin/su", "su", "/system/bin/su")) {
            try {
                val process = Runtime.getRuntime().exec(
                    arrayOf(su, "-c", "cat > $PATH && chmod 644 $PATH"),
                )
                DataOutputStream(process.outputStream).use { out ->
                    out.write(json.toByteArray(Charsets.UTF_8))
                }
                process.waitFor()
                if (readRaw()?.let { JSONObject(it).toString() } == JSONObject(json).toString()) {
                    return true
                }
            } catch (t: Throwable) {
                Log.i(TAG, "save via $su failed: $t")
            }
        }
        return false
    }

    private fun readRaw(): String? = try {
        FileInputStream(PATH).use { String(it.readBytes(), Charsets.UTF_8) }
    } catch (t: Throwable) {
        null
    }

    fun section(root: JSONObject, key: String): JSONObject =
        root.optJSONObject(key) ?: JSONObject().also { root.put(key, it) }

    fun packageNode(root: JSONObject, pkg: String): JSONObject {
        val packages = section(root, "packages")
        return packages.optJSONObject(pkg) ?: JSONObject().also { packages.put(pkg, it) }
    }

    fun activityNode(root: JSONObject, pkg: String, activity: String): JSONObject {
        val node = packageNode(root, pkg)
        val activities = section(node, "activities")
        return activities.optJSONObject(activity) ?: JSONObject().also { activities.put(activity, it) }
    }

    /** Read-only lookup: must not create nodes, or merely listing activities would
     *  litter the rule file with empty entries. */
    fun activityXr(root: JSONObject, pkg: String, activity: String): Boolean {
        val pkgNode = root.optJSONObject("packages")?.optJSONObject(pkg) ?: return false
        val node = pkgNode.optJSONObject("activities")?.optJSONObject(activity) ?: return false
        return node.optBoolean("xr", false)
    }

    fun setActivityXr(root: JSONObject, pkg: String, activity: String, xr: Boolean) {
        activityNode(root, pkg, activity).put("xr", xr)
    }

    fun identity(root: JSONObject, key: String, fallback: String): String {
        val node = section(root, "identity")
        val value = node.optString(key, "")
        return if (value.isEmpty()) fallback else value
    }

    fun setIdentity(root: JSONObject, key: String, value: String) {
        section(root, "identity").put(key, value)
    }
    // ---- per-app options -----------------------------------------------------

    fun optionBool(root: JSONObject, pkg: String, key: String, fallback: Boolean): Boolean =
        section(packageNode(root, pkg), "options").optBoolean(key, fallback)

    fun optionInt(root: JSONObject, pkg: String, key: String, fallback: Int): Int =
        section(packageNode(root, pkg), "options").optInt(key, fallback)

    fun optionString(root: JSONObject, pkg: String, key: String, fallback: String): String {
        val value = section(packageNode(root, pkg), "options").optString(key, "")
        return if (value.isEmpty()) fallback else value
    }

    fun setOptionBool(root: JSONObject, pkg: String, key: String, value: Boolean) {
        section(packageNode(root, pkg), "options").put(key, value)
    }

    fun setOptionInt(root: JSONObject, pkg: String, key: String, value: Int) {
        section(packageNode(root, pkg), "options").put(key, value)
    }

    fun setOptionString(root: JSONObject, pkg: String, key: String, value: String) {
        section(packageNode(root, pkg), "options").put(key, value)
    }

    /** Packages listed under identity.apply, empty when the default list is in use. */
    fun identityApply(root: JSONObject): List<String> {
        val array = root.optJSONObject("identity")?.optJSONArray("apply") ?: return emptyList()
        return (0 until array.length()).mapNotNull { array.optString(it, "").takeIf(String::isNotEmpty) }
    }

    fun setIdentityApply(root: JSONObject, packages: List<String>) {
        val identity = section(root, "identity")
        if (packages.isEmpty()) {
            identity.remove("apply")
        } else {
            identity.put("apply", org.json.JSONArray(packages))
        }
    }

    fun forceXr(root: JSONObject, pkg: String): Boolean =
        root.optJSONObject("packages")?.optJSONObject(pkg)?.optBoolean("forceXr", false) ?: false

    fun setForceXr(root: JSONObject, pkg: String, value: Boolean) {
        packageNode(root, pkg).put("forceXr", value)
    }
}
