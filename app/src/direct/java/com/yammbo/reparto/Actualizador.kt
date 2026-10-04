package com.yammbo.reparto

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Actualizacion en el sitio: el movil de quien reparte esta en la calle y
 * nadie va a bajarse un APK a mano cada vez que se toca algo.
 *
 * 🚨 La version se pregunta a NUESTRO servidor, no a GitHub. La API de GitHub
 * sin autenticar esta limitada a 60 peticiones/hora POR IP, y en redes moviles
 * con NAT compartido esa cuota se agota por culpa de terceros: la respuesta es
 * un 403 y la deteccion de actualizaciones falla en silencio, que es la peor
 * forma de fallar. GitHub queda solo de respaldo. (Misma leccion que en
 * Yammbo Music.) El APK si se descarga del CDN de GitHub: son megas y no tiene
 * sentido pagarlos en el VPS.
 */
object Actualizador {

    private const val TAG = "YammboReparto"
    private const val SONDA = "https://pos.yammbo.com/reparto/version.json"
    private const val GITHUB = "https://api.github.com/repos/yammbocom/Yammbo-Reparto/releases/latest"

    data class Version(val code: Int, val name: String, val url: String, val notas: String)

    /** Lo que hay publicado, o null si no se pudo averiguar. Bloquea: hilo aparte. */
    fun ultima(): Version? = sonda() ?: github()

    private fun leer(url: String, accept: String): String? {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Accept", accept)
            setRequestProperty("User-Agent", "Yammbo-Reparto-Updater")
            connectTimeout = 8_000
            readTimeout = 8_000
        }
        return try {
            if (c.responseCode != 200) { Log.d(TAG, url + " -> " + c.responseCode); null }
            else c.inputStream.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            Log.d(TAG, "sin respuesta de " + url + ": " + e.message); null
        } finally {
            c.disconnect()
        }
    }

    private fun sonda(): Version? = runCatching {
        val j = JSONObject(leer(SONDA, "application/json") ?: return null)
        val code = j.optInt("versionCode", 0)
        val name = j.optString("versionName").trim()
        val url = j.optString("url").trim()
        if (code > 0 && name.isNotBlank() && url.startsWith("https://"))
            Version(code, name, url, j.optString("notas").trim()) else null
    }.getOrNull()

    /** Respaldo. El codigo sale del ultimo tramo del tag: "v1.5" -> 5. */
    private fun github(): Version? = runCatching {
        val j = JSONObject(leer(GITHUB, "application/vnd.github+json") ?: return null)
        val name = j.optString("tag_name").removePrefix("v").trim()
        val code = name.substringAfterLast('.').toIntOrNull() ?: 0
        var url = ""
        val assets = j.optJSONArray("assets")
        for (i in 0 until (assets?.length() ?: 0)) {
            val a = assets!!.optJSONObject(i) ?: continue
            if (a.optString("name").endsWith(".apk")) {
                url = a.optString("browser_download_url"); break
            }
        }
        if (code > 0 && url.startsWith("https://"))
            Version(code, name, url, j.optString("body").trim()) else null
    }.getOrNull()

    fun hayNueva(ctx: Context, v: Version): Boolean = v.code > instalada(ctx)

    fun instalada(ctx: Context): Int = runCatching {
        val p = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) p.longVersionCode.toInt()
        else @Suppress("DEPRECATION") p.versionCode
    }.getOrDefault(0)

    fun nombreInstalado(ctx: Context): String = runCatching {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"
    }.getOrDefault("?")

    /**
     * Android NO permite instalar en silencio a una app normal: hace falta ser
     * propietario del dispositivo. Lo maximo honesto es descargar y abrir el
     * instalador del sistema, donde alguien toca "Actualizar" una vez.
     */
    fun puedeInstalar(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            ctx.packageManager.canRequestPackageInstalls()

    fun pedirPermisoInstalar(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        runCatching {
            ctx.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                    .setData(Uri.parse("package:" + ctx.packageName))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    /**
     * Descarga el APK y abre el instalador. Bloquea: llamar desde un hilo.
     * @return null si fue bien, o el motivo del fallo.
     */
    fun descargarEInstalar(ctx: Context, v: Version): String? {
        val destino = File(ctx.cacheDir, "actualizacion.apk")
        try {
            var url = v.url
            var con: HttpURLConnection
            var saltos = 0
            // GitHub responde con redireccion a su CDN; HttpURLConnection no
            // sigue los saltos entre http y https por su cuenta.
            while (true) {
                con = (URL(url).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "Yammbo-Reparto-Updater")
                    connectTimeout = 15_000
                    readTimeout = 60_000
                }
                val code = con.responseCode
                if (code in 301..308 && saltos++ < 5) {
                    val siguiente = con.getHeaderField("Location")
                    con.disconnect()
                    if (siguiente.isNullOrBlank()) return "La descarga no llegó a ninguna parte"
                    url = siguiente
                    continue
                }
                if (code != 200) { con.disconnect(); return "La descarga respondió " + code }
                break
            }
            con.inputStream.use { ent ->
                destino.outputStream().use { sal -> ent.copyTo(sal, 64 * 1024) }
            }
            con.disconnect()
        } catch (e: Exception) {
            return "No se pudo descargar: " + (e.message ?: "error")
        }
        if (destino.length() < 100_000) return "El archivo descargado no parece un APK"

        return try {
            val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".updates", destino)
            ctx.startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
                    )
            )
            null
        } catch (e: Exception) {
            "No se pudo abrir el instalador: " + (e.message ?: "error")
        }
    }

    /** Una comprobacion al dia basta; no hay que gastar red en cada arranque. */
    fun tocaMirar(ctx: Context): Boolean {
        val p = ctx.getSharedPreferences("yammbo_reparto", Context.MODE_PRIVATE)
        val ultima = p.getLong("update_check", 0L)
        val ahora = System.currentTimeMillis()
        if (ahora - ultima < 24 * 60 * 60 * 1000L) return false
        p.edit().putLong("update_check", ahora).apply()
        return true
    }
}
