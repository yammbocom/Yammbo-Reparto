package com.yammbo.reparto

import android.content.Context
import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/** Las llamadas que hace la app. Todas bloquean: nunca desde el hilo principal. */
object Api {

    private const val TAG = "YammboReparto"

    /**
     * El idioma del movil viaja en cada peticion.
     *
     * La pantalla es una pagina del servidor y sus errores tambien vienen de
     * ahi ("ese pedido ya lo lleva otra persona"). Sin esta cabecera, una app en
     * ingles ensenaria mensajes en castellano en el peor momento: cuando algo
     * ha salido mal.
     */
    private fun idioma(): String {
        val l = Locale.getDefault()
        val tag = l.language.ifBlank { "es" }
        return tag + "-" + (l.country.ifBlank { tag.uppercase() }) + "," + tag + ";q=0.9"
    }

    private fun abrir(url: String, metodo: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = metodo
            setRequestProperty("accept", "application/json")
            setRequestProperty("accept-language", idioma())
            setRequestProperty("User-Agent", "Yammbo-Delivery")
            connectTimeout = 8_000
            readTimeout = 8_000
        }

    /** El cuerpo, o null si no se pudo. null NO significa "vacio". */
    fun datos(url: String): String? {
        val c = abrir(url, "GET")
        return try {
            val code = c.responseCode
            if (code != 200) { Log.w(TAG, "data HTTP " + code); null }
            else c.inputStream.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            Log.d(TAG, "sin respuesta: " + e.message); null
        } finally {
            c.disconnect()
        }
    }

    /**
     * Publica donde esta. Es lo que le mantiene "disponible": el servidor da
     * por caducado un punto de mas de tres minutos.
     */
    fun posicion(url: String, lat: Double, lng: Double, precision: Float?): Boolean {
        val c = abrir(url, "POST")
        return try {
            c.doOutput = true
            c.setRequestProperty("content-type", "application/json")
            val cuerpo = "{\"lat\":" + lat + ",\"lng\":" + lng +
                (if (precision != null) ",\"precision\":" + precision else "") + "}"
            c.outputStream.use { it.write(cuerpo.toByteArray()) }
            c.responseCode == 200
        } catch (e: Exception) {
            Log.d(TAG, "no se pudo publicar la posicion: " + e.message); false
        } finally {
            c.disconnect()
        }
    }

    /** Retira el punto: deja de estar disponible en el acto. */
    fun borrarPosicion(url: String): Boolean {
        val c = abrir(url, "DELETE")
        return try { c.responseCode == 200 } catch (e: Exception) { false } finally { c.disconnect() }
    }

    /**
     * Aceptar o rechazar. Devuelve null si fue bien, o el motivo.
     *
     * Quien decide es el SERVIDOR: dos repartidores tocando "Aceptar" a la vez
     * leerian los dos "sin asignar", asi que el que llega segundo recibe aqui
     * el "ya lo lleva otra persona" y hay que ensenarselo tal cual.
     */
    fun accion(ctx: Context, url: String): String? {
        val c = abrir(url, "POST")
        return try {
            val code = c.responseCode
            if (code == 200) return null
            val cuerpo = runCatching {
                c.errorStream?.bufferedReader()?.use { it.readText() }
            }.getOrNull()
            runCatching { org.json.JSONObject(cuerpo ?: "").optString("error") }
                .getOrNull()?.ifBlank { null }
                ?: ctx.getString(R.string.err_generico, code)
        } catch (e: Exception) {
            ctx.getString(R.string.err_sin_conexion)
        } finally {
            c.disconnect()
        }
    }
}
