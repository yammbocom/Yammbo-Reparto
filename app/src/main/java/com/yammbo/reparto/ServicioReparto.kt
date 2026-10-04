package com.yammbo.reparto

import android.Manifest
import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * El turno: mantiene la ubicacion publicada y avisa de los pedidos.
 *
 * Es un servicio en primer plano de tipo `location`. Ese tipo es el que permite
 * seguir leyendo el GPS con la pantalla apagada sin pedir el permiso de
 * ubicacion en segundo plano, siempre que se arranque con la app visible — que
 * es justo lo que hace MainActivity.
 *
 * Reparto de trabajo con la pantalla web, igual que en la app de cocina: si la
 * pagina esta delante, ella publica la posicion y lee la lista, y este bucle se
 * aparta. Pero "estar delante" no es lo mismo que "estar trayendo datos": si el
 * wifi se va o el token se revoca, la pagina se queda muda y aqui se toma el
 * relevo pasados [SIN_DATOS_MS].
 */
class ServicioReparto : Service(), LocationListener {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var bucle: Job? = null
    private var wake: PowerManager.WakeLock? = null

    /**
     * Falso desde que empieza onDestroy. Una vuelta del bucle que estaba
     * esperando a la red cuando se termino el turno no puede volver a pintar
     * la notificacion (quedaria una "Turno activo" huerfana) ni anunciar un
     * pedido a quien ya se fue a casa.
     */
    @Volatile private var vivo = true
    private val cerrojo = Any()

    /** Que ningun POST de posicion quede por detras del DELETE final. */
    private val retirada = Retirada()

    private var pos: Location? = null
    private var ultimoEnvio = 0L
    private var ultimoPunto: Location? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Aviso.crearCanales(this)
        // 🚨 Un servicio de tipo `location` sin permiso de ubicacion lanza al
        // entrar en primer plano (Android 14+). Tragarse esa excepcion no
        // arregla nada: un servicio arrancado como foreground que nunca llega a
        // serlo lo mata el sistema a los pocos segundos con
        // ForegroundServiceDidNotStartInTimeException, y eso cierra la app.
        // Si no se puede, se para aqui y ya.
        val enPie = runCatching {
            startForeground(1, Aviso.notificacionServicio(this, getString(R.string.estado_buscando)))
        }.onFailure { Log.w(TAG, "sin primer plano: " + it.message) }.isSuccess
        if (!enPie) { stopSelf(); return }
        // Un reinicio del sistema (START_STICKY) con el turno terminado no lo
        // vuelve a empezar: terminar el turno es una decision, no un estado
        // que se pierde al matar el proceso.
        if (!Prefs(this).turnoActivo) { stopSelf(); return }
        Vigia.ponerTurno(Turno.BUSCANDO)
        runCatching {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "yammbo:reparto-svc")
                .also { it.acquire() }
        }
        pedirUbicacion()
        arrancar()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // onCreate pudo pararlo (sin primer plano o con el turno terminado) y
        // el sistema llama aqui igual: sin esto el bucle volveria a publicar.
        if (!vivo || !Prefs(this).turnoActivo) return START_NOT_STICKY
        if (bucle?.isActive != true) arrancar()
        return START_STICKY
    }

    override fun onTimeout(startId: Int) {
        Log.w(TAG, "el sistema pide parar el servicio")
        stopSelf()
    }

    // ── ubicacion ───────────────────────────────────────────────────────────

    private fun hayPermiso(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    // hayPermiso() ya lo comprueba y cada llamada va en runCatching: lint no
    // reconoce la comprobacion cuando esta en otra funcion.
    @SuppressLint("MissingPermission")
    private fun pedirUbicacion() {
        if (!hayPermiso()) { Log.w(TAG, "sin permiso de ubicacion"); return }
        val lm = getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
        // Los dos proveedores: el GPS es preciso pero tarda y no ve dentro de un
        // edificio; el de red responde al instante y basta para saber si un
        // pedido cae cerca o lejos.
        for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            runCatching {
                if (lm.isProviderEnabled(p)) {
                    lm.requestLocationUpdates(p, 10_000L, 15f, this, Looper.getMainLooper())
                }
            }.onFailure { Log.w(TAG, "no se pudo pedir " + p + ": " + it.message) }
        }
        runCatching {
            // Arrancar con el ultimo punto conocido evita el minuto en blanco
            // inicial en el que la app no sabria decir ninguna distancia.
            lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
        }.getOrNull()?.let { if (esUtil(it)) guardar(it) }
    }

    /** Un punto de hace media hora o con 2 km de error no sirve para nada. */
    private fun esUtil(l: Location): Boolean {
        if (l.latitude == 0.0 && l.longitude == 0.0) return false
        if (l.hasAccuracy() && l.accuracy > 1_000f) return false
        val edad = System.currentTimeMillis() - l.time
        return edad < 10 * 60 * 1000L
    }

    private fun guardar(l: Location) {
        pos = l
        Aviso.ultimaLat = l.latitude
        Aviso.ultimaLng = l.longitude
    }

    override fun onLocationChanged(l: Location) {
        if (!esUtil(l)) return
        // Entre el GPS y la red, quedarse con el mas preciso mientras el otro no
        // sea claramente mas reciente.
        val actual = pos
        val mejor = actual == null ||
            l.time - actual.time > 30_000L ||
            (l.hasAccuracy() && actual.hasAccuracy() && l.accuracy < actual.accuracy)
        if (mejor) guardar(l)
    }

    @Deprecated("Obligatorio en API < 29; el sistema lo llama igual")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}

    // ── bucle ───────────────────────────────────────────────────────────────

    /**
     * Mientras la pagina esta delante y trayendo datos, ella se encarga: es la
     * que ya publica la posicion y lee la lista, y duplicarlo solo gastaria
     * bateria y escrituras.
     */
    private fun meToca(): Boolean {
        if (!Vigia.enPrimerPlano) return true
        return !Vigia.hayDatosRecientes(SIN_DATOS_MS)
    }

    private fun arrancar() {
        bucle?.cancel()
        bucle = scope.launch {
            val prefs = Prefs(this@ServicioReparto)
            var espera = PAUSA_MS
            while (isActive && vivo) {
                if (!prefs.configurada) { estado(Turno.SIN_ENLACE); delay(5_000); continue }
                if (!hayPermiso()) { estado(Turno.SIN_PERMISO); delay(10_000); continue }
                if (!meToca()) { delay(3_000); espera = PAUSA_MS; continue }

                if (!vivo) break
                publicarPosicion(prefs)
                if (!vivo) break

                val cuerpo = Api.datos(prefs.urlDatos())
                if (!vivo) break
                val d = Reparto.leer(cuerpo)
                if (d == null) {
                    // Un fetch fallido NO es una lista vacia: no se toca nada y
                    // se afloja el ritmo en vez de insistir.
                    espera = minOf(espera * 2, TOPE_MS)
                } else {
                    espera = PAUSA_MS
                    prefs.unidad = d.unidad
                    when {
                        !d.disponible -> estado(Turno.NO_DISPONIBLE)
                        d.mios.isNotEmpty() -> estado(Turno.LLEVANDO, d.mios.size)
                        else -> estado(Turno.DISPONIBLE)
                    }
                    val nuevas = Vigia.nuevas(d)
                    if (nuevas.isNotEmpty() && Vigia.debeAvisar(this@ServicioReparto, d)) {
                        anunciar(nuevas.first(), d.unidad)
                    }
                }
                delay(espera)
            }
        }
    }

    /**
     * Publica donde esta. El latido es obligatorio aunque no se haya movido:
     * el servidor da por caducado un punto de mas de tres minutos, y quien
     * espera parado en la puerta del local dejaria de constar como disponible
     * justo cuando mas lo esta.
     */
    private fun publicarPosicion(prefs: Prefs) {
        val l = pos ?: return
        val ahora = System.currentTimeMillis()
        val previo = ultimoPunto
        val lejos = previo == null ||
            Reparto.millas(previo.latitude, previo.longitude, l.latitude, l.longitude) > 0.015
        val toca = (lejos && ahora - ultimoEnvio >= MOVIDO_MS) || ahora - ultimoEnvio >= LATIDO_MS
        if (!toca) return
        if (!vivo || !retirada.empezarEnvio()) return
        val ok = Api.posicion(prefs.urlPos(), l.latitude, l.longitude, if (l.hasAccuracy()) l.accuracy else null)
        // El turno se cerro mientras el POST viajaba: pudo llegar despues del
        // DELETE de onDestroy y dejarle "disponible" hasta que el punto caduque.
        // Se repite el DELETE, que ahora si es lo ultimo que recibe el servidor.
        if (retirada.terminarEnvio() || !vivo) {
            runCatching { Api.borrarPosicion(prefs.urlPos()) }
            return
        }
        if (ok) {
            ultimoEnvio = ahora
            ultimoPunto = l
        }
    }

    private fun anunciar(o: Oferta, unidad: String) {
        if (!vivo) return
        val t = Textos.de(this)
        val lineas = listOfNotNull(
            Reparto.lineaDistancia(o, Aviso.ultimaLat, Aviso.ultimaLng, unidad, t),
            o.direccion.ifBlank { t.sinDireccion },
            Reparto.lineaCobro(o, t),
        )
        // Cerrada cabe en una linea; desplegada, un dato por renglon.
        Aviso.notificar(
            this, getString(R.string.aviso_titulo, o.clave),
            lineas.joinToString(" · "), lineas.joinToString("\n"),
        )
        Aviso.sonar(this)
        Aviso.despertar(this)
        // Si no hay permiso de superposicion, el cartel no sale. La
        // notificacion ya se mando, asi que al menos algo llega — pero la
        // pantalla de ajustes lo dice bien claro para que se conceda.
        if (!Aviso.ofrecer(this, o, unidad) {}) {
            Log.w(TAG, "sin permiso de superposicion: no hay cartel")
        }
    }

    /**
     * El mismo estado a la notificacion permanente y a la pantalla. Los textos
     * son los de siempre: la notificacion tiene que decir la verdad sobre el GPS.
     */
    private fun estado(t: Turno, n: Int = 0) {
        val texto = when (t) {
            Turno.SIN_ENLACE -> getString(R.string.estado_sin_enlace)
            Turno.SIN_PERMISO -> getString(R.string.estado_sin_permiso)
            Turno.NO_DISPONIBLE -> getString(R.string.estado_no_disponible)
            Turno.LLEVANDO -> getString(R.string.estado_llevando, n)
            Turno.DISPONIBLE -> getString(R.string.estado_disponible)
            Turno.BUSCANDO, Turno.APAGADO -> getString(R.string.estado_buscando)
        }
        synchronized(cerrojo) {
            if (!vivo) return
            Vigia.ponerTurno(t, n)
            runCatching {
                val nm = getSystemService(android.app.NotificationManager::class.java)
                nm?.notify(1, Aviso.notificacionServicio(this, texto))
            }
        }
    }

    override fun onDestroy() {
        synchronized(cerrojo) {
            vivo = false
            retirada.cerrar()
            // La del servicio se va sola al pararlo; esto cubre una que se
            // hubiera repintado justo antes.
            runCatching { getSystemService(android.app.NotificationManager::class.java)?.cancel(1) }
        }
        runCatching {
            (getSystemService(Context.LOCATION_SERVICE) as? LocationManager)?.removeUpdates(this)
        }
        runCatching { wake?.release() }
        Vigia.ponerTurno(Turno.APAGADO)
        // Al cerrar el turno se retira el punto: dejar de tener la app abierta
        // tiene que dejar de publicar donde esta uno. El DELETE sale cuando el
        // bucle ya ha parado (un POST en vuelo no se interrumpe al cancelar),
        // con un tope por si la red se queda colgada; y si aun asi un POST
        // acaba despues, publicarPosicion repite el DELETE.
        val prefs = Prefs(this)
        val ultimo = bucle
        bucle?.cancel()
        if (prefs.configurada) {
            Thread {
                runCatching { runBlocking { withTimeoutOrNull(ESPERA_BUCLE_MS) { ultimo?.join() } } }
                runCatching { Api.borrarPosicion(prefs.urlPos()) }
            }.start()
        }
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "YammboReparto"
        private const val PAUSA_MS = 12_000L
        private const val TOPE_MS = 60_000L
        /** Margen sobre los 10 s de la pagina antes de darla por muda. */
        private const val SIN_DATOS_MS = 25_000L
        /** Minimo entre envios cuando se ha movido de verdad. */
        private const val MOVIDO_MS = 15_000L
        /** Latido: como mucho este hueco sin publicar nada. */
        private const val LATIDO_MS = 45_000L
        /** Lo que tarda como mucho un POST colgado (8 s conectar + 8 s leer). */
        private const val ESPERA_BUCLE_MS = 20_000L

        /**
         * Sin permiso de ubicacion ni se intenta: ver el comentario de onCreate.
         * Con el turno terminado, tampoco: solo [empezarTurno] lo vuelve a abrir.
         */
        fun arrancar(ctx: Context) {
            if (!Prefs(ctx).turnoActivo) { Log.i(TAG, "turno terminado: no se arranca"); return }
            val hayPermiso =
                ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) ==
                    PackageManager.PERMISSION_GRANTED ||
                    ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                    PackageManager.PERMISSION_GRANTED
            if (!hayPermiso) { Log.w(TAG, "sin permiso de ubicacion: no se arranca"); return }
            val i = Intent(ctx, ServicioReparto::class.java)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
                else ctx.startService(i)
            }.onFailure { Log.w(TAG, "no se pudo arrancar: " + it.message) }
        }

        fun parar(ctx: Context) {
            runCatching { ctx.stopService(Intent(ctx, ServicioReparto::class.java)) }
        }

        /** El boton "Empezar turno": guarda la decision y arranca como siempre. */
        fun empezarTurno(ctx: Context) {
            val prefs = Prefs(ctx)
            prefs.turnoActivo = true
            if (Jornada.debeArrancar(prefs.configurada, true)) arrancar(ctx)
        }

        /**
         * El boton "Terminar turno": para el servicio, y con el el latido, el
         * GPS y el cartel. onDestroy retira el punto del servidor, asi que se
         * deja de constar como disponible en el acto.
         */
        fun terminarTurno(ctx: Context) {
            Prefs(ctx).turnoActivo = false
            parar(ctx)
        }
    }
}
