package it.osmus.rx.remote

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Local list of approved devices. Contains no credentials and survives relay restarts. */
object DeviceRoster {
    private const val FILE = "rx_known_devices_v1"
    private const val KEY = "devices"
    private val idPattern = Regex("[0-9a-f-]{36}")

    fun remember(context: Context, device: RxApi.Device) {
        if (!idPattern.matches(device.id)) return
        val known = known(context).associateBy { it.id }.toMutableMap()
        known[device.id] = device.copy(online = false)
        val all = JSONArray()
        known.values.take(64).forEach {
            all.put(JSONObject().put("id", it.id).put("name", it.name))
        }
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putString(KEY, all.toString()).apply()
    }

    fun known(context: Context): List<RxApi.Device> = runCatching {
        val text = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getString(KEY, "[]") ?: "[]"
        val array = JSONArray(text)
        (0 until minOf(array.length(), 64)).mapNotNull { i ->
            val item = array.getJSONObject(i)
            val id = item.optString("id")
            val name = item.optString("name").take(60)
            if (idPattern.matches(id)) RxApi.Device(id, name.ifBlank { "GALLERY" }, false)
            else null
        }
    }.getOrDefault(emptyList())

    fun clear(context: Context) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().clear().apply()
    }
}
