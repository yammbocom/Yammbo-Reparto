package com.yammbo.reparto

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.GeolocationPermissions
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

/**
 * La pantalla: la web de reparto dentro de un WebView, con una condicion que la
 * web sola no puede imponer.
 *
 * 🚨 Sin ubicacion no se entra. No es un aviso que se pueda cerrar: si el movil
 * no dice donde esta, quien lo lleva no cuenta como disponible, el servidor no
 * le ofrece nada y el cliente no puede ver moverse su pedido. Ensenar la lista
 * en ese estado seria ensenar botones que van a fallar.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var raiz: LinearLayout
    private lateinit var web: WebView
    private var bloqueo: View? = null
    private var pidiendo = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        raiz = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }
        setContentView(raiz)
        // Los insets se aplican al CONTENEDOR y no al WebView: puestos en el
        // WebView, la pagina se dibujaba igual debajo de la barra de estado.
        aplicarInsets(raiz)

        raiz.addView(barraSuperior())

        web = WebView(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
            setBackgroundColor(Color.BLACK)
        }
        raiz.addView(web)
        configurarWeb()

        Aviso.crearCanales(this)
        pedirNotificaciones()
    }

    // ── barra ───────────────────────────────────────────────────────────────

    private fun barraSuperior(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundColor(Color.BLACK)
        setPadding(dp(14), dp(8), dp(10), dp(8))
        addView(TextView(this@MainActivity).apply {
            text = getString(R.string.app_name)
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        })
        addView(TextView(this@MainActivity).apply {
            text = getString(R.string.ajustes)
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(dp(16), dp(9), dp(16), dp(9))
            background = GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                setStroke(dp(1), Color.parseColor("#3A3A3A"))
                cornerRadius = 999f
            }
            setOnClickListener {
                startActivity(Intent(this@MainActivity, AjustesActivity::class.java))
            }
        })
    }

    private fun dp(v: Int): Int =
        (v * resources.displayMetrics.density).toInt().coerceAtLeast(if (v > 0) 1 else 0)

    private fun aplicarInsets(v: View) {
        ViewCompat.setOnApplyWindowInsetsListener(v) { vista, insets ->
            val b = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            vista.setPadding(b.left, b.top, b.right, b.bottom)
            insets
        }
    }

    // ── web ─────────────────────────────────────────────────────────────────

    @SuppressLint("SetJavaScriptEnabled")
    private fun configurarWeb() {
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            // La pagina usa navigator.geolocation. Sin esto, dentro de la app
            // no habria distancias y la pantalla se quedaria bloqueada para
            // siempre pidiendo una ubicacion que nunca podria dar.
            setGeolocationEnabled(true)
            cacheMode = WebSettings.LOAD_DEFAULT
            mediaPlaybackRequiresUserGesture = false
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onGeolocationPermissionsShowPrompt(
                origin: String?, callback: GeolocationPermissions.Callback?,
            ) {
                // Se concede sin preguntar porque el permiso de verdad, el de
                // Android, ya se pidio y sin el no se llega hasta aqui.
                callback?.invoke(origin, tienePermisoUbicacion(), false)
            }
        }
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(v: WebView?, r: WebResourceRequest?): Boolean {
                val u = r?.url ?: return false
                // El mapa y el telefono son del sistema: dentro del WebView no
                // funcionarian y dejarian la pagina en blanco.
                if (u.scheme == "tel" || u.scheme == "geo" || u.host?.contains("google") == true) {
                    runCatching { startActivity(Intent(Intent.ACTION_VIEW, u)) }
                    return true
                }
                return false
            }
        }
    }

    private fun cargar() {
        val prefs = Prefs(this)
        if (!prefs.configurada) {
            startActivity(Intent(this, AjustesActivity::class.java))
            return
        }
        if (web.url == null) web.loadUrl(prefs.url)
    }

    // ── ubicacion: la condicion para usar la app ────────────────────────────

    private fun tienePermisoUbicacion(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /** El permiso concedido no basta: el GPS del movil puede estar apagado. */
    private fun ubicacionEncendida(): Boolean = runCatching {
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
            lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }.getOrDefault(false)

    private fun revisarPuerta() {
        val ok = tienePermisoUbicacion() && ubicacionEncendida()
        if (ok) {
            quitarBloqueo()
            cargar()
            ServicioReparto.arrancar(this)
        } else {
            mostrarBloqueo()
            // El servicio no se para: si el GPS vuelve, esta listo. Pero sin
            // permiso no publica nada, asi que tampoco aparece disponible.
            if (!pidiendo) pedirUbicacion()
        }
    }

    private fun pedirUbicacion() {
        if (tienePermisoUbicacion()) return
        pidiendo = true
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
            10,
        )
    }

    private fun pedirNotificaciones() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) return
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 11)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        pidiendo = false
        if (requestCode == 10) revisarPuerta()
    }

    private fun mostrarBloqueo() {
        if (bloqueo != null) return
        val hayPermiso = tienePermisoUbicacion()
        val v = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.BLACK)
            setPadding(dp(28), dp(40), dp(28), dp(40))
            layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)

            addView(TextView(this@MainActivity).apply {
                text = "Activa tu ubicación"
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
            })
            addView(TextView(this@MainActivity).apply {
                text = if (!hayPermiso)
                    "Sin ella no apareces como disponible y no te llegan pedidos. " +
                        "Es lo único que la app necesita para funcionar."
                else
                    "El permiso está concedido, pero la ubicación del móvil está apagada. " +
                        "Enciéndela para volver a estar disponible."
                setTextColor(Color.parseColor("#9E9E9E"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                gravity = Gravity.CENTER
                setPadding(0, dp(12), 0, 0)
            })
            addView(TextView(this@MainActivity).apply {
                text = if (!hayPermiso) "Dar permiso" else "Abrir ajustes de ubicación"
                gravity = Gravity.CENTER
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setTextColor(Color.BLACK)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setPadding(dp(24), dp(15), dp(24), dp(15))
                background = GradientDrawable().apply {
                    setColor(Color.WHITE); cornerRadius = 999f
                }
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(26) }
                setOnClickListener {
                    if (!tienePermisoUbicacion()) {
                        // Si ya lo denegaron "para siempre", el dialogo no
                        // vuelve a salir: hay que llevarles a los ajustes o se
                        // quedan tocando un boton que no hace nada.
                        if (ActivityCompat.shouldShowRequestPermissionRationale(
                                this@MainActivity, Manifest.permission.ACCESS_FINE_LOCATION)
                        ) pedirUbicacion()
                        else abrirAjustesApp()
                    } else {
                        runCatching {
                            startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                        }
                    }
                }
            })
        }
        bloqueo = v
        raiz.addView(v)
        web.visibility = View.GONE
    }

    private fun quitarBloqueo() {
        bloqueo?.let { raiz.removeView(it) }
        bloqueo = null
        web.visibility = View.VISIBLE
    }

    private fun abrirAjustesApp() {
        runCatching {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.parse("package:" + packageName))
            )
        }
    }

    // ── ciclo de vida ───────────────────────────────────────────────────────

    override fun onResume() {
        super.onResume()
        Vigia.enPrimerPlano = true
        Aviso.quitar(this)
        Aviso.quitarNotificacion(this)
        revisarPuerta()
        mirarActualizacion()
    }

    override fun onPause() {
        super.onPause()
        Vigia.enPrimerPlano = false
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (web.url != null) web.reload()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        (web.parent as? ViewGroup)?.removeView(web)
        web.destroy()
        super.onDestroy()
    }

    // ── actualizacion ───────────────────────────────────────────────────────

    private fun mirarActualizacion() {
        if (!Actualizador.tocaMirar(this)) return
        Thread {
            val v = Actualizador.ultima() ?: return@Thread
            if (!Actualizador.hayNueva(this, v)) return@Thread
            runOnUiThread { ofrecerActualizacion(v) }
        }.start()
    }

    private fun ofrecerActualizacion(v: Actualizador.Version) {
        if (isFinishing) return
        AlertDialog.Builder(this)
            .setTitle("Versión " + v.name)
            .setMessage(v.notas.ifBlank { "Hay una versión nueva de la app." })
            .setNegativeButton("Ahora no", null)
            .setPositiveButton("Actualizar") { _, _ ->
                if (!Actualizador.puedeInstalar(this)) {
                    Toast.makeText(this, "Permite instalar apps de esta fuente", Toast.LENGTH_LONG).show()
                    Actualizador.pedirPermisoInstalar(this)
                    return@setPositiveButton
                }
                Toast.makeText(this, "Descargando…", Toast.LENGTH_SHORT).show()
                Thread {
                    val error = Actualizador.descargarEInstalar(this, v)
                    if (error != null) runOnUiThread {
                        Toast.makeText(this, error, Toast.LENGTH_LONG).show()
                    }
                }.start()
            }
            .show()
    }
}
