package com.yammbo.reparto

import android.content.Context

/**
 * Lo que sabe ESTE movil. El enlace de reparto es una URL con token: vive solo
 * aqui, no se manda a ningun sitio nuestro, y si el encargado lo revoca en el
 * panel deja de valer solo.
 */
class Prefs(ctx: Context) {
    private val p = ctx.getSharedPreferences("yammbo_reparto", Context.MODE_PRIVATE)

    var url: String
        get() = p.getString("url", "") ?: ""
        set(v) = p.edit().putString("url", v.trim().trimEnd('/')).apply()

    /** "mi" o "km". La manda el servidor; se guarda para pintar sin esperar. */
    var unidad: String
        get() = p.getString("unidad", "mi") ?: "mi"
        set(v) = p.edit().putString("unidad", if (v == "km") "km" else "mi").apply()

    var sonido: Boolean
        get() = p.getBoolean("sonido", true)
        set(v) = p.edit().putBoolean("sonido", v).apply()

    var encima: Boolean
        get() = p.getBoolean("encima", true)
        set(v) = p.edit().putBoolean("encima", v).apply()

    // https y no http: el manifest lleva usesCleartextTraffic=false, asi que
    // una URL en claro fallaria siempre sin decir por que.
    val configurada: Boolean get() = url.startsWith("https://") && url.contains("/repartidor/")

    fun urlDatos(): String = url + "/data"
    fun urlPos(): String = url + "/pos"
    fun urlAccion(orderId: String, accion: String): String = url + "/" + orderId + "/" + accion
}
