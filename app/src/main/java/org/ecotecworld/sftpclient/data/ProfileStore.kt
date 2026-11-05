package org.ecotecworld.sftpclient.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

enum class AuthType { PASSWORD, KEY }

/** A saved server. Passwords and key passphrases are never stored; they are asked for on connect. */
data class Profile(
    val id: String,
    val name: String,
    val host: String,
    val port: Int,
    val username: String,
    val authType: AuthType,
    val keyName: String? = null,
) {
    val displayName: String get() = name.ifBlank { "$username@$host" }
}

/** Saved connections in app-private storage. Imported private keys are kept in files/keys/. */
class ProfileStore(context: Context) {
    private val prefs = context.getSharedPreferences("profiles", Context.MODE_PRIVATE)
    private val keyDir = File(context.filesDir, "keys")

    fun load(): List<Profile> {
        val json = prefs.getString("list", null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(json)
            List(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                Profile(
                    id = o.getString("id"),
                    name = o.optString("name"),
                    host = o.getString("host"),
                    port = o.optInt("port", 22),
                    username = o.getString("username"),
                    authType = runCatching { AuthType.valueOf(o.getString("auth")) }.getOrDefault(AuthType.PASSWORD),
                    keyName = if (o.has("keyName")) o.getString("keyName") else null,
                )
            }
        }.getOrDefault(emptyList())
    }

    fun save(profiles: List<Profile>) {
        val arr = JSONArray()
        profiles.forEach { p ->
            arr.put(JSONObject().apply {
                put("id", p.id)
                put("name", p.name)
                put("host", p.host)
                put("port", p.port)
                put("username", p.username)
                put("auth", p.authType.name)
                p.keyName?.let { put("keyName", it) }
            })
        }
        prefs.edit().putString("list", arr.toString()).apply()
    }

    fun keyFile(profileId: String) = File(keyDir, profileId)

    fun writeKey(profileId: String, bytes: ByteArray) {
        keyDir.mkdirs()
        keyFile(profileId).writeBytes(bytes)
    }

    fun deleteKey(profileId: String) {
        keyFile(profileId).delete()
    }
}
