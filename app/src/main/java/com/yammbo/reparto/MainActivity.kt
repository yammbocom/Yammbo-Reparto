package com.yammbo.reparto

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.GeolocationPermissions
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged

/**
 * La pantalla: la web de reparto dentro de un WebView, con una condicion que la
 * web sola no puede imponer.
 *
 * 🚨 Sin ubicacion no se entra. No es un aviso que se pueda cerrar: si el movil
 * no dice donde esta, quien lo lleva no cuenta como disponible, el servidor no
 * le ofrece nada y el cliente no puede ver moverse su pedido. Ensenar la lista
 * en ese estado seria ensenar botones que van a fallar.
 *
 * Por encima de la web va una cabecera nativa con el estado del turno, el
 * mismo que dice la notificacion permanente. Si la web no carga, una tarjeta a
 * pantalla completa con Reintentar sustituye a la pagina de error del WebView.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var raiz: FrameLayout
    private lateinit var web: WebView
    private lateinit var p: Ui.Paleta

    // La pantalla principal: barra, cabecera de estado y la web.
    private var principal: View? = null
    private var chip: TextView? = null
    private var subEstado: TextView? = null
    /** Donde va el boton Empezar/Terminar turno: cambia de tipo segun el estado. */
    private var ranuraTurno: FrameLayout? = null
    private var progreso: ProgressBar? = null
    private var hueco: FrameLayout? = null
    private var tarjetaFallo: View? = null
    /** La tarjeta de "Fuera de turno" que tapa la web, o null con el turno empezado. */
    private var tarjetaFuera: View? = null
    /** Borrar el historial al acabar de cargar: venia de la pagina en blanco. */
    private var limpiarHistorial = false

    private var bloqueo: View? = null
    /**
     * Pestillo de un solo uso: ya se abrio el dialogo del sistema en esta
     * instancia. A partir de ahi, sin "rationale" el boton lleva a los ajustes
     * (el sistema ya no volveria a preguntar).
     *
     * No es "hay una peticion en curso". Ponerlo a false en algun sitio es
     * justo el fallo que se arreglo: el sistema contesta al instante cuando
     * descarta una peticion, y con un flag que se apaga ahi se vuelve a pedir
     * en bucle hasta desbordar la pila.
     */
    private var yaPedidos = false

    /** El enlace que tiene cargado el WebView, para recargar si cambia en Ajustes. */
    private var cargada: String? = null
    /** Con que turno se cargo: la web solo recibe ubicacion con el turno empezado. */
    private var cargadaConTurno: Boolean? = null
    /** Por que no se ve la web, o null si se ve. */
    private var fallo: Fallo? = null
    /** Lo escrito en el campo del enlace, para no perderlo al cambiar de tema. */
    private var borrador: String? = null
    private var red: ConnectivityManager.NetworkCallback? = null

    private sealed interface Fallo {
        data object SinRed : Fallo
        /** 404 y compania: el token se revoco o se copio mal. */
        data object Enlace : Fallo
        data class Servidor(val codigo: Int) : Fallo
    }

    private val escuchaTurno: () -> Unit = { runOnUiThread { pintarTurno() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        raiz = FrameLayout(this)
        setContentView(raiz)
        // Los insets se aplican al CONTENEDOR y no al WebView: puestos en el
        // WebView, la pagina se dibujaba igual debajo de la barra de estado.
        Ui.insets(raiz)

        web = WebView(this)
        configurarWeb()
        construir()

        onBackPressedDispatcher.addCallback(this, atras)
        Aviso.crearCanales(this)
    }

    /**
     * Monta la pantalla con la paleta del modo actual. Se vuelve a llamar al
     * cambiar entre claro y oscuro (la actividad declara `uiMode` en
     * configChanges para no recargar la web), y el WebView se reengancha tal
     * cual, sin perder la pagina.
     */
    private fun construir() {
        p = Ui.paleta(this)
        Ui.barrasSistema(this, p)
        raiz.setBackgroundColor(p.fondo)
        (web.parent as? ViewGroup)?.removeView(web)
        raiz.removeAllViews()
        bloqueo = null
        tarjetaFallo = null
        tarjetaFuera = null
        web.setBackgroundColor(p.fondo)

        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val ajustes = Ui.botonIcono(this, p, R.drawable.ic_ajustes, getString(R.string.ajustes)) {
            startActivity(Intent(this, AjustesActivity::class.java))
        }
        col.addView(Ui.barraSuperior(this, p, getString(R.string.app_name), ajustes))

        // Cabecera de estado: el chip grande, una linea que dice que significa
        // y, al lado, el boton para empezar o terminar el turno.
        val c = Ui.chip(this, p, getString(R.string.chip_apagado), lleno = false, grande = true)
        val s = Ui.texto(this, "", Ui.Sp.SECUNDARIO, p.texto2)
        val ranura = FrameLayout(this)
        col.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(Ui.MARGEN), dp(4), dp(Ui.MARGEN), dp(16))
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(c, LinearLayout.LayoutParams(-2, -2))
                addView(s, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
                // TalkBack anuncia el cambio sin que haya que ir a buscarlo.
                accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(ranura, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(12) })
        })
        col.addView(Ui.separador(this, p))

        val barra = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            isIndeterminate = false
            progressTintList = ColorStateList.valueOf(p.texto)
            progressBackgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
            visibility = View.INVISIBLE
        }
        col.addView(barra, LinearLayout.LayoutParams(-1, dp(2)))

        val h = FrameLayout(this)
        h.addView(web, FrameLayout.LayoutParams(-1, -1))
        col.addView(h, LinearLayout.LayoutParams(-1, 0, 1f))

        raiz.addView(col, FrameLayout.LayoutParams(-1, -1))
        principal = col
        chip = c
        subEstado = s
        ranuraTurno = ranura
        progreso = barra
        hueco = h
        pintarTurno()
        if (fallo != null) mostrarFallo()
    }

    private fun dp(v: Int): Int = Ui.dp(this, v)

    // ── estado del turno ────────────────────────────────────────────────────

    /**
     * Relleno = disponible o llevando pedidos; contorno = cualquier otra cosa.
     * Con el turno terminado es "Fuera de turno" aunque el servicio aun no
     * haya publicado su parada (ver Jornada.visible).
     */
    private fun pintarTurno() {
        val c = chip ?: return
        val activo = Prefs(this).turnoActivo
        val visible = Jornada.visible(Vigia.turno, activo)
        val (t, sub) = when (visible) {
            Turno.DISPONIBLE ->
                getString(R.string.estado_disponible) to getString(R.string.cab_disponible)
            Turno.LLEVANDO ->
                getString(R.string.estado_llevando, Vigia.llevando) to getString(R.string.noti_turno_activo)
            Turno.NO_DISPONIBLE ->
                getString(R.string.chip_no_disponible) to getString(R.string.cab_no_disponible)
            Turno.BUSCANDO ->
                getString(R.string.chip_buscando) to getString(R.string.estado_buscando)
            Turno.SIN_ENLACE ->
                getString(R.string.chip_sin_enlace) to getString(R.string.estado_sin_enlace)
            Turno.SIN_PERMISO ->
                getString(R.string.chip_sin_permiso) to getString(R.string.estado_sin_permiso)
            Turno.APAGADO ->
                getString(R.string.chip_apagado) to
                    getString(if (activo) R.string.cab_apagado else R.string.cab_fuera)
        }
        Ui.pintarChip(c, p, t, Jornada.lleno(visible))
        subEstado?.text = sub
        pintarBotonTurno(activo)
        pintarFuera()
    }

    /** Empezar es la accion que se busca (relleno); terminar, la secundaria. */
    private fun pintarBotonTurno(activo: Boolean) {
        val r = ranuraTurno ?: return
        r.removeAllViews()
        r.addView(
            if (activo) Ui.boton(this, p, getString(R.string.turno_terminar), Ui.Tipo.SECUNDARIO,
                compacto = true) { confirmarTerminar() }
            else Ui.boton(this, p, getString(R.string.turno_empezar), Ui.Tipo.PRIMARIO,
                compacto = true) { empezarTurno() }
        )
    }

    private fun empezarTurno() {
        ServicioReparto.empezarTurno(this)
        pintarTurno()
        cargar()
    }

    /**
     * Se pregunta antes: un toque sin querer dejaria a alguien sin pedidos sin
     * enterarse, y al cliente sin ver por donde va su comida.
     */
    private fun confirmarTerminar() {
        if (isFinishing) return
        AlertDialog.Builder(this)
            .setTitle(R.string.turno_confirmar_titulo)
            .setMessage(R.string.turno_confirmar_texto)
            .setNegativeButton(R.string.turno_cancelar, null)
            .setPositiveButton(R.string.turno_terminar) { _, _ ->
                ServicioReparto.terminarTurno(this)
                pintarTurno()
                cargar()
            }
            .show()
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
                // Con el turno terminado, la pagina tampoco recibe ubicacion:
                // si no, seguiria publicando el punto ella sola mientras la
                // app esta delante.
                callback?.invoke(
                    origin, tienePermisoUbicacion() && Prefs(this@MainActivity).turnoActivo, false,
                )
            }

            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progreso?.progress = newProgress
                progreso?.visibility = if (newProgress < 100) View.VISIBLE else View.INVISIBLE
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

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                progreso?.visibility = View.VISIBLE
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                progreso?.visibility = View.INVISIBLE
                if (limpiarHistorial && url != "about:blank") {
                    limpiarHistorial = false
                    view?.clearHistory()
                }
            }

            override fun onReceivedError(
                view: WebView?, request: WebResourceRequest?, error: WebResourceError?,
            ) {
                // Solo la pagina en si: una imagen que no carga no es "sin conexion".
                if (request?.isForMainFrame != true) return
                val codigo = error?.errorCode ?: ERROR_UNKNOWN
                // ERROR_UNKNOWN es tambien una navegacion abortada; taparlo con
                // una tarjeta de error ocultaria una pagina que funciona.
                if (codigo == ERROR_UNKNOWN) return
                fallo = when (codigo) {
                    ERROR_HOST_LOOKUP, ERROR_CONNECT, ERROR_TIMEOUT, ERROR_IO -> Fallo.SinRed
                    else -> Fallo.Servidor(codigo)
                }
                mostrarFallo()
            }

            override fun onReceivedHttpError(
                view: WebView?, request: WebResourceRequest?, errorResponse: WebResourceResponse?,
            ) {
                if (request?.isForMainFrame != true) return
                val codigo = errorResponse?.statusCode ?: 0
                fallo = if (codigo == 401 || codigo == 403 || codigo == 404 || codigo == 410) Fallo.Enlace
                else Fallo.Servidor(codigo)
                mostrarFallo()
            }
        }
    }

    private fun cargar() {
        val prefs = Prefs(this)
        if (!prefs.configurada) return
        pintarFuera()
        if (!prefs.turnoActivo) {
            // Fuera de turno no hay pagina: la tarjeta nativa la tapa, y la web
            // se descarga para que su JS no siga publicando ni pidiendo datos
            // (ni ensenando su bloque de "hace falta la ubicacion").
            fallo = null
            quitarFallo()
            if (cargada != null) {
                web.stopLoading()
                web.loadUrl("about:blank")
                cargada = null
            }
            cargadaConTurno = false
            return
        }
        // Si el enlace cambio en Ajustes, se carga el nuevo: el viejo podria
        // estar revocado y seguiria ensenando la lista de otro token.
        if (web.url == null || cargada != prefs.url) {
            // Tras la pagina en blanco de fuera de turno, que "atras" no vuelva a ella.
            limpiarHistorial = web.url != null
            cargada = prefs.url
            cargadaConTurno = prefs.turnoActivo
            fallo = null
            quitarFallo()
            web.loadUrl(prefs.url)
        } else if (cargadaConTurno != prefs.turnoActivo) {
            // Recargar es lo que corta (o devuelve) la ubicacion a la pagina:
            // el permiso de geolocalizacion de la web se decide al pedirlo.
            cargadaConTurno = prefs.turnoActivo
            fallo = null
            quitarFallo()
            web.reload()
        }
    }

    /** Tarjeta a pantalla completa en vez de la pagina de error del WebView. */
    private fun mostrarFallo() {
        val h = hueco ?: return
        quitarFallo()
        // Fuera de turno no hay pagina que fallar: un error tardio de la que
        // se acaba de descargar no tapa la tarjeta de empezar el turno.
        if (tarjetaFuera != null) { fallo = null; return }
        val f = fallo ?: return
        val irAjustes = { startActivity(Intent(this, AjustesActivity::class.java)) }
        val paso = when (f) {
            Fallo.SinRed -> Ui.paso(
                this, p, Ui.ilustracion(this, p, R.drawable.ic_sin_red), null,
                getString(R.string.err_sin_conexion), getString(R.string.web_sin_red_texto),
                getString(R.string.reintentar), { reintentar() },
            )
            Fallo.Enlace -> Ui.paso(
                this, p, Ui.ilustracion(this, p, R.drawable.ic_sin_red), null,
                getString(R.string.web_enlace_titulo), getString(R.string.web_enlace_texto),
                getString(R.string.web_cambiar_enlace), irAjustes,
                otro = getString(R.string.reintentar), alOtro = { reintentar() },
            )
            is Fallo.Servidor -> Ui.paso(
                this, p, Ui.ilustracion(this, p, R.drawable.ic_sin_red), null,
                getString(R.string.web_error_titulo), getString(R.string.error_servidor, f.codigo),
                getString(R.string.reintentar), { reintentar() },
            )
        }
        tarjetaFallo = paso.vista
        h.addView(paso.vista, FrameLayout.LayoutParams(-1, -1))
    }

    private fun quitarFallo() {
        tarjetaFallo?.let { hueco?.removeView(it) }
        tarjetaFallo = null
    }

    /**
     * Fuera de turno, una tarjeta nativa tapa la web entera: el simbolo,
     * "Fuera de turno" y el boton para empezar. Con el turno empezado se quita.
     * Es idempotente: se llama en cada repintado del estado.
     */
    private fun pintarFuera() {
        val h = hueco ?: return
        val prefs = Prefs(this)
        val toca = prefs.configurada && !prefs.turnoActivo
        if (!toca) {
            tarjetaFuera?.let { h.removeView(it) }
            tarjetaFuera = null
            return
        }
        if (tarjetaFuera?.parent === h) return
        val paso = Ui.paso(
            this, p, Ui.ilustracion(this, p, R.drawable.ic_noti), null,
            getString(R.string.chip_apagado), getString(R.string.cab_fuera),
            getString(R.string.turno_empezar), { empezarTurno() },
        )
        paso.vista.setBackgroundColor(p.fondo)
        // Encima de todo, tambien de una tarjeta de fallo.
        h.addView(paso.vista, FrameLayout.LayoutParams(-1, -1))
        tarjetaFuera = paso.vista
    }

    private fun reintentar() {
        fallo = null
        quitarFallo()
        if (web.url == null) { cargada = null; cargar() } else web.reload()
    }

    /**
     * Sin conexion, la tarjeta se reintenta sola en cuanto vuelve la red: quien
     * va en moto no va a tocar Reintentar al salir de un tunel.
     */
    private fun vigilarRed(si: Boolean) {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        if (!si) {
            red?.let { runCatching { cm.unregisterNetworkCallback(it) } }
            red = null
            return
        }
        if (red != null) return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                runOnUiThread { if (fallo == Fallo.SinRed) reintentar() }
            }
        }
        runCatching { cm.registerDefaultNetworkCallback(cb); red = cb }
    }

    // ── primera vez: el enlace ──────────────────────────────────────────────

    /**
     * Paso 1: pegar el enlace de reparto. Antes de guardarlo se comprueba
     * contra el servidor, para que "no vale" y "no hay internet" se digan por
     * separado y en el momento, no despues como una pantalla en blanco.
     */
    private fun mostrarEmparejar() {
        quitarBloqueo()
        val campo = Ui.campo(this, p, "https://pos.yammbo.com/repartidor/…", p.superficie).apply {
            setText(borrador ?: Prefs(this@MainActivity).url)
            contentDescription = getString(R.string.aj_enlace)
        }
        val error = Ui.texto(this, "", Ui.Sp.SECUNDARIO, p.fondo, medio = true).apply {
            // Invertido: lo que pide atencion se dice con contraste, no con color.
            background = Ui.forma(this@MainActivity, p.texto, 12f)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            visibility = View.GONE
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        campo.doAfterTextChanged {
            borrador = it?.toString()
            error.visibility = View.GONE
        }
        val bloque = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(Ui.etiqueta(this@MainActivity, getString(R.string.aj_enlace), p.texto2),
                LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
            addView(campo)
            addView(error, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
            addView(Ui.texto(this@MainActivity, getString(R.string.emparejar_ayuda),
                Ui.Sp.SECUNDARIO, p.texto2),
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
            layoutParams = LinearLayout.LayoutParams(-1, -2)
        }
        var boton: TextView? = null
        val paso = Ui.paso(
            this, p, Ui.logo(this, p, 72),
            getString(R.string.app_name) + " · " + getString(R.string.paso_n_de, 1, PASOS),
            getString(R.string.bienvenida_titulo), getString(R.string.bienvenida_texto),
            getString(R.string.emparejar_boton), { boton?.let { conectar(campo, error, it) } },
            extra = bloque,
        )
        boton = paso.boton
        campo.setOnEditorActionListener { _, id, _ ->
            if (id == EditorInfo.IME_ACTION_DONE) { conectar(campo, error, paso.boton); true } else false
        }
        ponerBloqueo(paso.vista)
    }

    private fun conectar(campo: EditText, error: TextView, boton: TextView) {
        val v = campo.text.toString().trim().trimEnd('/')
        fun fallar(msg: String, reintento: Boolean) {
            error.text = msg
            error.visibility = View.VISIBLE
            boton.text = getString(if (reintento) R.string.reintentar else R.string.emparejar_boton)
        }
        if (v.isEmpty()) { fallar(getString(R.string.emparejar_vacio), false); return }
        // La misma regla que Ajustes y que Prefs.configurada.
        if (!(v.startsWith("https://") && v.contains("/repartidor/"))) {
            fallar(getString(R.string.aj_enlace_malo), false); return
        }
        error.visibility = View.GONE
        Ui.habilitar(boton, false)
        boton.text = getString(R.string.emparejar_comprobando)
        getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(campo.windowToken, 0)
        Thread {
            val codigo = Api.comprobar("$v/data")
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                Ui.habilitar(boton, true)
                when {
                    Api.esValido(codigo) -> {
                        Prefs(this).url = v
                        borrador = null
                        revisarPuerta()
                    }
                    codigo < 0 -> fallar(getString(R.string.emparejar_sin_red), true)
                    codigo in 400..499 -> fallar(getString(R.string.emparejar_no_vale), false)
                    else -> fallar(getString(R.string.error_servidor, codigo), true)
                }
            }
        }.start()
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

    private fun faltanNotificaciones(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED

    /**
     * Tres requisitos, en orden, y ninguno se puede saltar.
     *
     * 1. **El enlace**: sin el no hay de quien ser repartidor.
     * 2. **Ubicacion**: sin ella no se es disponible y no hay nada que ensenar.
     * 3. **Dibujar encima**: sin ello un pedido llega como una notificacion mas,
     *    y quien va conduciendo o mirando el mapa no la ve. El cartel con
     *    Aceptar y Rechazar ES la app; sin el, esto no sirve para lo que se
     *    hizo.
     *
     * Se piden de uno en uno: dos pantallas de permisos a la vez terminan con
     * alguien tocando "no" a las dos.
     */
    private fun revisarPuerta() {
        if (!Prefs(this).configurada) {
            mostrarEmparejar()
            return
        }

        if (!tienePermisoUbicacion() || !ubicacionEncendida()) {
            val hayPermiso = tienePermisoUbicacion()
            mostrarBloqueo(
                2, R.drawable.ic_noti,
                getString(R.string.puerta_ubicacion_titulo),
                getString(
                    if (hayPermiso) R.string.puerta_ubicacion_apagada
                    else R.string.puerta_ubicacion_permiso
                ),
                if (!hayPermiso && faltanNotificaciones()) getString(R.string.puerta_ubicacion_notis)
                else null,
                getString(
                    if (hayPermiso) R.string.puerta_ubicacion_ajustes
                    else R.string.puerta_ubicacion_boton
                ),
            ) {
                if (!tienePermisoUbicacion()) {
                    // La primera vez, el dialogo del sistema. Despues, si ya lo
                    // denegaron "para siempre", el dialogo no vuelve a salir:
                    // hay que llevarles a los ajustes o se quedan tocando un
                    // boton que no hace nada.
                    if (!yaPedidos || ActivityCompat.shouldShowRequestPermissionRationale(
                            this, Manifest.permission.ACCESS_FINE_LOCATION)
                    ) pedirPermisos()
                    else abrirAjustesApp()
                } else {
                    runCatching { startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
                }
            }
            // El dialogo del sistema NO se abre solo: lo abre el boton, despues
            // de haber leido para que se usa la ubicacion. Es la divulgacion
            // destacada que exige Google Play.
            return
        }

        if (!Aviso.puedeDibujarEncima(this)) {
            mostrarBloqueo(
                3, R.drawable.ic_capas,
                getString(R.string.puerta_cartel_titulo),
                getString(R.string.puerta_cartel_texto),
                getString(R.string.puerta_cartel_pista),
                getString(R.string.puerta_cartel_boton),
            ) { Aviso.pedirPermisoEncima(this) }
            return
        }

        quitarBloqueo()
        cargar()
        // Con el turno empezado, como siempre; terminado, no se arranca nada.
        val prefs = Prefs(this)
        if (Jornada.debeArrancar(prefs.configurada, prefs.turnoActivo)) ServicioReparto.arrancar(this)
    }

    /**
     * TODOS los permisos de golpe, en UNA sola peticion.
     *
     * 🚨 Android solo admite una peticion de permisos a la vez. La segunda no
     * falla: el framework la descarta y llama a onRequestPermissionsResult EN
     * EL ACTO con arrays vacios (Activity.java, "Can request only one set of
     * permissions at a time"). Pedir ubicacion en onResume mientras seguia viva
     * la de notificaciones de onCreate provocaba esa llamada sincrona, que
     * volvia a pedir, que volvia a ser descartada... hasta desbordar la pila.
     * Se cerraba SIEMPRE en el primer arranque y nunca despues, porque a la
     * segunda ya no habia dos peticiones que chocaran.
     */
    private fun permisosQueFaltan(): Array<String> {
        val l = ArrayList<String>(3)
        if (!tienePermisoUbicacion()) {
            l.add(Manifest.permission.ACCESS_FINE_LOCATION)
            l.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        if (faltanNotificaciones()) l.add(Manifest.permission.POST_NOTIFICATIONS)
        return l.toTypedArray()
    }

    private fun pedirPermisos() {
        val faltan = permisosQueFaltan()
        if (faltan.isEmpty()) return
        yaPedidos = true
        ActivityCompat.requestPermissions(this, faltan, 10)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // 🚨 De aqui NO se vuelve a pedir nada, ni siquiera con los arrays
        // vacios que significan "cancelado". Ese era el motor de la recursion:
        // el guardia se apagaba justo antes y no servia de nada.
        // Volver a pedir es cosa del boton de la pantalla de bloqueo.
        revisarPuerta()
    }

    /** Pantalla de paso. Se repinta siempre: el texto cambia según qué falte. */
    private fun mostrarBloqueo(
        paso: Int, icono: Int, titulo: String, texto: String, nota: String?,
        etiqueta: String, alTocar: () -> Unit,
    ) {
        quitarBloqueo()
        val extra = nota?.let {
            Ui.texto(this, it, Ui.Sp.SECUNDARIO, p.texto2).apply { gravity = Gravity.CENTER }
        }
        val v = Ui.paso(
            this, p, Ui.ilustracion(this, p, icono), getString(R.string.paso_n_de, paso, PASOS),
            titulo, texto, etiqueta, alTocar, extra = extra,
        )
        ponerBloqueo(v.vista)
    }

    private fun ponerBloqueo(v: View) {
        bloqueo = v
        raiz.addView(v, FrameLayout.LayoutParams(-1, -1))
        principal?.visibility = View.GONE
        web.visibility = View.GONE
    }

    private fun quitarBloqueo() {
        bloqueo?.let { raiz.removeView(it) }
        bloqueo = null
        principal?.visibility = View.VISIBLE
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
        Vigia.alCambiarTurno = escuchaTurno
        pintarTurno()
        vigilarRed(true)
        Aviso.quitar(this)
        Aviso.quitarNotificacion(this)
        revisarPuerta()
        mirarActualizacion()
    }

    override fun onPause() {
        super.onPause()
        Vigia.enPrimerPlano = false
        if (Vigia.alCambiarTurno === escuchaTurno) Vigia.alCambiarTurno = null
        vigilarRed(false)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val oscuro = (newConfig.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        if (oscuro != p.oscura) {
            construir()
            revisarPuerta()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (web.url != null) {
            fallo = null
            quitarFallo()
            // Por cargar(): recarga con el turno de ahora y deja cargadaConTurno
            // al dia (fuera de turno no carga nada y pone la tarjeta).
            cargadaConTurno = null
            cargar()
        }
    }

    /** Atras navega dentro de la web antes de salir, como siempre. */
    private val atras = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            if (bloqueo == null && tarjetaFuera == null && web.canGoBack()) { web.goBack(); return }
            isEnabled = false
            onBackPressedDispatcher.onBackPressed()
            isEnabled = true
        }
    }

    override fun onDestroy() {
        (web.parent as? ViewGroup)?.removeView(web)
        web.destroy()
        super.onDestroy()
    }

    // ── actualizacion ───────────────────────────────────────────────────────

    private fun mirarActualizacion() {
        if (!BuildConfig.SELF_UPDATE) return
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
            .setTitle(getString(R.string.act_titulo, v.name))
            .setMessage(v.notas.ifBlank { getString(R.string.act_generico) })
            .setNegativeButton(R.string.act_ahora_no, null)
            .setPositiveButton(R.string.act_actualizar) { _, _ ->
                if (!Actualizador.puedeInstalar(this)) {
                    Toast.makeText(this, R.string.act_permiso_instalar, Toast.LENGTH_LONG).show()
                    Actualizador.pedirPermisoInstalar(this)
                    return@setPositiveButton
                }
                Toast.makeText(this, getString(R.string.act_descargando, v.name), Toast.LENGTH_SHORT).show()
                Thread {
                    val error = Actualizador.descargarEInstalar(this, v)
                    if (error != null) runOnUiThread {
                        Toast.makeText(this, error, Toast.LENGTH_LONG).show()
                    }
                }.start()
            }
            .show()
    }

    companion object {
        /** Enlace, ubicacion y cartel. */
        private const val PASOS = 3
    }
}
