package com.carlos_jean.escutaplayerapp

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class PlaylistStore(context: Context) {
    private val prefs = context.getSharedPreferences("playlists", Context.MODE_PRIVATE)

    fun all(): Map<String, List<Long>> {
        val json = JSONObject(prefs.getString("data", "{}") ?: "{}")
        val result = linkedMapOf<String, List<Long>>()
        json.keys().forEach { name ->
            val arr = json.getJSONArray(name)
            result[name] = List(arr.length()) { arr.getLong(it) }
        }
        return result
    }

    private fun save(map: Map<String, List<Long>>) {
        val json = JSONObject()
        map.forEach { (name, ids) -> json.put(name, JSONArray(ids)) }
        prefs.edit().putString("data", json.toString()).apply()
    }

    fun create(name: String) {
        val m = all().toMutableMap()
        if (!m.containsKey(name)) {
            m[name] = emptyList()
            save(m)
        }
    }

    fun delete(name: String) {
        val m = all().toMutableMap()
        m.remove(name)
        save(m)
    }

    fun addSong(name: String, id: Long) {
        val m = all().toMutableMap()
        val list = m[name].orEmpty()
        if (id !in list) {
            m[name] = list + id
            save(m)
        }
    }

    fun removeSong(name: String, id: Long) {
        val m = all().toMutableMap()
        m[name] = m[name].orEmpty() - id
        save(m)
    }
}