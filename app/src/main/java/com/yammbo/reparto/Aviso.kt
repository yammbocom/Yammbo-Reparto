package com.yammbo.reparto

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * El aviso de que hay un pedido: notificacion, sonido y el cartel encima de lo
 * que haya delante.
 *
 * A diferencia del de cocina, este cartel PIDE UNA DECISION. Quien reparte va
 * conduciendo o andando y no puede abrir la app, buscar el pedido y decidir:
 * o acepta ahi mismo o lo rechaza ahi mismo.
 */
object Aviso {

    private const val CANAL_OFERTAS = "ofertas_v1"
    private const val CANAL_SERVICIO = "turno_v1"
    private const val ID_OFERTA = 42

    private var cartel: View? = null

    fun crearCanales(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return

        // 🚨 Los ajustes de un canal son INMUTABLES una vez creado: si luego
        // hace falta otro sonido, hay que crear un canal con id nuevo. De ahi
        // el sufijo de version en el id.
        if (nm.getNotificationChannel(CANAL_OFERTAS) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CANAL_OFERTAS,
                    ctx.getString(R.string.canal_ofertas),
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = ctx.getString(R.string.canal_ofertas_desc)
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0, 320, 180, 320)
                    // Tono de ALARMA y uso ALARM: el de notificacion no se oye
                    // en un bolsillo, con casco o con el movil en silencio, y
                    // ahi es exactamente donde esta este movil.
                    setSound(
                        RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                            ?: Settings.System.DEFAULT_NOTIFICATION_URI,
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build(),
                    )
                }
            )
        }
        if (nm.getNotificationChannel(CANAL_SERVICIO) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CANAL_SERVICIO,
                    ctx.getString(R.string.canal_servicio),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply { description = ctx.getString(R.string.canal_servicio_desc) }
            )
        }
    }

    /**
     * La notificacion del servicio dice la verdad sobre el GPS.
     *
     * Es permanente y se lee de reojo: si pone "En turno" cuando en realidad no
     * se esta publicando la posicion, alguien va a pasarse una hora creyendo
     * que esta disponible.
     */
    fun notificacionServicio(ctx: Context, texto: String): Notification {
        val abrir = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(ctx, CANAL_SERVICIO)
        else @Suppress("DEPRECATION") Notification.Builder(ctx)
        // "Turno activo" arriba y la verdad sobre el GPS debajo: el titulo dice
        // que el servicio corre, el texto si de verdad se esta disponible.
        b.setContentTitle(ctx.getString(R.string.noti_turno_activo))
            .setContentText(texto)
            .setSmallIcon(R.drawable.ic_noti)
            .setOngoing(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setContentIntent(abrir)
        // Sin la espera de 10 s que Android 12+ aplica a las notificaciones de
        // servicio: quien entra al turno tiene que verlo en el acto.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }
        return b.build()
    }

    /**
     * @param detalle el texto desplegado (un dato por renglon); si falta, el
     *   mismo `cuerpo`.
     */
    fun notificar(ctx: Context, titulo: String, cuerpo: String, detalle: String = cuerpo) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        val abrir = PendingIntent.getActivity(
            ctx, 1, Intent(ctx, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(ctx, CANAL_OFERTAS)
        else @Suppress("DEPRECATION") Notification.Builder(ctx).setPriority(Notification.PRIORITY_HIGH)
        b.setContentTitle(titulo)
            .setContentText(cuerpo)
            .setStyle(Notification.BigTextStyle().setBigContentTitle(titulo).bigText(detalle))
            .setSmallIcon(R.drawable.ic_noti)
            .setAutoCancel(true)
            .setContentIntent(abrir)
        // Google Play restringe la intencion a pantalla completa: solo en `direct`.
        if (BuildConfig.FULL_SCREEN_ALERT) b.setFullScreenIntent(abrir, true)
        nm.notify(ID_OFERTA, b.build())
    }

    fun quitarNotificacion(ctx: Context) {
        runCatching { ctx.getSystemService(NotificationManager::class.java)?.cancel(ID_OFERTA) }
    }

    /** En movil silenciado, la notificacion no suena: el tono va aparte. */
    fun sonar(ctx: Context) {
        if (!Prefs(ctx).sonido) return
        runCatching {
            val uri: Uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: Settings.System.DEFAULT_NOTIFICATION_URI
            val r = RingtoneManager.getRingtone(ctx.applicationContext, uri) ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                r.audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            }
            r.play()
            Handler(Looper.getMainLooper()).postDelayed({ runCatching { r.stop() } }, 3_500)
        }
    }

    /** Enciende la pantalla: un cartel que nadie ve no ha avisado de nada. */
    fun despertar(ctx: Context) {
        runCatching {
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
            @Suppress("DEPRECATION")
            val wl = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "yammbo:reparto-aviso",
            )
            wl.acquire(6_000)
        }
    }

    fun puedeDibujarEncima(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(ctx)

    fun pedirPermisoEncima(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        runCatching {
            ctx.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + ctx.packageName),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    /**
     * El cartel con la decision.
     *
     * Superficie INVERTIDA respecto al sistema (negra en modo claro, blanca en
     * oscuro): es lo unico de la app que pide atencion, y se dice con
     * contraste, no con color. Lo que decide un si o un no (a cuanto y a
     * donde) va arriba y en grande.
     *
     * @return false si no se pudo dibujar (falta el permiso de superposicion).
     *   Quien llama tiene que enterarse: fallar en silencio aqui deja a alguien
     *   creyendo que le avisan cuando no.
     */
    fun ofrecer(ctx: Context, o: Oferta, unidad: String, alDecidir: () -> Unit): Boolean {
        if (!puedeDibujarEncima(ctx)) return false
        val prefs = Prefs(ctx)
        val t = Textos.de(ctx)
        Handler(Looper.getMainLooper()).post {
            runCatching {
                quitar(ctx)
                val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                val q = Ui.invertida(ctx)
                fun dp(v: Int) = Ui.dp(ctx, v)

                fun linea(texto: String, sp: Float, color: Int, arriba: Int, medio: Boolean = false) =
                    Ui.texto(ctx, texto, sp, color, medio).apply {
                        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(arriba) }
                    }

                // Lo que se lee puede crecer con la letra grande del movil: va
                // en un scroll y los botones quedan siempre a la vista.
                val contenido = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(Ui.etiqueta(ctx, ctx.getString(R.string.cartel_encabezado), q.texto2))
                    addView(linea(o.clave, Ui.Sp.TITULO_GRANDE, q.texto, 4, medio = true))
                    // La distancia es lo que decide un si o un no, asi que va
                    // arriba y en grande, no escondida en el detalle.
                    Reparto.lineaDistancia(o, ultimaLat, ultimaLng, unidad, t)?.let {
                        addView(linea(it, Ui.Sp.TITULO, q.texto, 8, medio = true))
                    }

                    addView(Ui.separador(ctx, q).apply {
                        (layoutParams as LinearLayout.LayoutParams).topMargin = dp(16)
                    })
                    addView(Ui.etiqueta(ctx, ctx.getString(R.string.cartel_entrega), q.texto2).apply {
                        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) }
                    })
                    addView(linea(o.direccion.ifBlank { t.sinDireccion }, Ui.Sp.TITULO, q.texto, 4, medio = true))
                    if (o.cliente.isNotBlank()) {
                        addView(linea(o.cliente, Ui.Sp.CUERPO, q.texto2, 4))
                    }

                    val detalle = Reparto.detalle(o, t)
                    if (detalle.isNotEmpty()) {
                        addView(LinearLayout(ctx).apply {
                            orientation = LinearLayout.VERTICAL
                            background = Ui.forma(ctx, q.superficie, 12f)
                            setPadding(dp(16), dp(12), dp(16), dp(12))
                            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) }
                            detalle.forEachIndexed { i, d ->
                                val sangrada = d.startsWith(" ")
                                addView(
                                    linea(
                                        d.trim(),
                                        if (sangrada) Ui.Sp.SECUNDARIO else Ui.Sp.CUERPO,
                                        if (sangrada) q.texto2 else q.texto,
                                        if (i == 0) 0 else if (sangrada) 4 else 6,
                                    )
                                )
                            }
                        })
                    }
                    // El cobro sale de la regla de siempre (Reparto.lineaCobro),
                    // con los datos reales del pedido. Los de la tienda web
                    // llegan pagados ("no cobres nada"); los de WhatsApp o el
                    // agente pueden ser contra entrega y traen `cobrar`. Ese es
                    // el dato que cuesta dinero si se pasa por alto, asi que se
                    // destaca invirtiendo el bloque, con el importe grande y en
                    // negrita. Sin color.
                    if (o.pagadoOnline) {
                        addView(linea(Reparto.lineaCobro(o, t), Ui.Sp.CUERPO, q.texto, 16, medio = true))
                    } else {
                        addView(LinearLayout(ctx).apply {
                            orientation = LinearLayout.VERTICAL
                            background = Ui.forma(ctx, q.texto, 12f)
                            setPadding(dp(16), dp(12), dp(16), dp(14))
                            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) }
                            addView(Ui.texto(ctx, Reparto.dinero(o.cobrar, o.moneda),
                                Ui.Sp.TITULO_GRANDE, q.fondo).apply { typeface = Ui.NEGRITA })
                            addView(linea(Reparto.lineaCobro(o, t), Ui.Sp.CUERPO, q.fondo, 2, medio = true))
                        })
                    }
                }

                val tarjeta = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    background = Ui.forma(ctx, q.fondo, 20f, borde = q.linea, bordeDp = 1f)
                    elevation = dp(12).toFloat()
                    setPadding(dp(24), dp(24), dp(24), dp(20))
                    // Peso 1 con alto "lo que ocupe": si no cabe, el scroll
                    // encoge y los botones no se salen de la pantalla.
                    addView(ScrollView(ctx).apply {
                        isVerticalScrollBarEnabled = false
                        addView(contenido)
                    }, LinearLayout.LayoutParams(-1, -2, 1f))
                    addView(LinearLayout(ctx).apply {
                        orientation = LinearLayout.HORIZONTAL
                        addView(
                            Ui.boton(ctx, q, ctx.getString(R.string.cartel_rechazar), Ui.Tipo.SECUNDARIO) {
                                decidir(ctx, o, "rechazar", alDecidir)
                            },
                            LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(12) },
                        )
                        addView(
                            Ui.boton(ctx, q, ctx.getString(R.string.cartel_aceptar), Ui.Tipo.PRIMARIO) {
                                decidir(ctx, o, "tomar", alDecidir)
                            },
                            LinearLayout.LayoutParams(0, -2, 1.2f),
                        )
                    }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(20) })
                }

                // Marco a pantalla completa PERO sin cerrar al tocar fuera: este
                // cartel pide una decision, y descartarlo con el pulgar sin
                // querer deja el pedido esperando sin que nadie lo sepa.
                val m = dp(16)
                val marco = FrameLayout(ctx).apply {
                    setPadding(m, m * 2, m, m * 2)
                    addView(tarjeta, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
                    isClickable = true
                    // La tarjeta no se mete debajo de la barra de estado ni de
                    // la de navegacion.
                    ViewCompat.setOnApplyWindowInsetsListener(this) { v, ins ->
                        val b = ins.getInsets(
                            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
                        )
                        v.setPadding(m + b.left, m + b.top, m + b.right, m + b.bottom)
                        WindowInsetsCompat.CONSUMED
                    }
                }

                val tipo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
                val lp = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    tipo,
                    // NOT_FOCUSABLE no impide los toques: la ventana sigue
                    // recibiendolos, solo no roba el teclado ni el foco a la
                    // app de mapas que haya delante.
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                        WindowManager.LayoutParams.FLAG_DIM_BEHIND,
                    android.graphics.PixelFormat.TRANSLUCENT,
                ).apply {
                    gravity = Gravity.CENTER
                    dimAmount = 0.72f
                }
                wm.addView(marco, lp)
                cartel = marco

                // Se retira solo al minuto. No se decide por nadie: el pedido
                // sigue en la lista y en la notificacion, que no caduca.
                // Comparar identidad, no nulidad: si entretanto entro otro
                // pedido, el temporizador del anterior apagaria el cartel nuevo.
                Handler(Looper.getMainLooper()).postDelayed(
                    { if (cartel === marco) quitar(ctx) }, 60_000,
                )
            }
        }
        return prefs.encima
    }

    /** La ultima posicion conocida, para poder decir "a 1,2 mi de ti". */
    @Volatile var ultimaLat: Double? = null
    @Volatile var ultimaLng: Double? = null

    private fun decidir(ctx: Context, o: Oferta, accion: String, alDecidir: () -> Unit) {
        quitar(ctx)
        quitarNotificacion(ctx)
        // Se apunta ya para que el siguiente ciclo no lo vuelva a ofrecer
        // mientras la peticion viaja.
        Vigia.olvidar(o.orderId)
        val app = ctx.applicationContext
        Thread {
            val prefs = Prefs(app)
            val error = Api.accion(app, prefs.urlAccion(o.orderId, accion))
            Handler(Looper.getMainLooper()).post {
                if (error != null) {
                    // Que lo haya cogido otro es lo normal, no un fallo: se
                    // dice y ya. Callarlo dejaria a alguien saliendo hacia una
                    // direccion que ya lleva su companero.
                    Toast.makeText(app, error, Toast.LENGTH_LONG).show()
                } else if (accion == "tomar") {
                    Toast.makeText(app, app.getString(R.string.aviso_aceptado, o.clave), Toast.LENGTH_SHORT).show()
                    app.startActivity(
                        Intent(app, MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    )
                }
                alDecidir()
            }
        }.start()
    }

    fun quitar(ctx: Context) {
        val v = cartel ?: return
        cartel = null
        runCatching {
            (ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(v)
        }
    }

    /**
     * Dispara el aviso completo para probarlo sin esperar a un pedido real.
     *
     * 🚨 El ejemplo va **pagado en linea**, que es lo unico que puede llegar
     * hoy: la tienda fija `payment_method='online'` y esta pantalla solo recibe
     * pedidos de la web. Un ejemplo que dijera "cobrar en efectivo" enseñaria a
     * quien reparte a pedir dinero por algo que ya esta cobrado, y eso se
     * aprende con el primer cliente enfadado, no con el segundo.
     */
    fun probar(ctx: Context): Boolean {
        val app = ctx.applicationContext
        crearCanales(app)
        val o = Oferta(
            orderId = "00000000-0000-0000-0000-000000000000",
            clave = "W-1042",
            direccion = "1234 W 54th St, Los Angeles",
            lat = null, lng = null,
            cliente = "Frank Test",
            telefono = "3105550000",
            nota = "Timbre roto, llamar al llegar",
            cobrar = null, moneda = "USD", metodoPago = "online",
            millasLocal = 2.31, mio = false,
            articulos = listOf("2  Tacos al pastor", "1  Horchata"),
        )
        notificar(app, app.getString(R.string.aviso_titulo, o.clave), o.direccion)
        sonar(app)
        despertar(app)
        return ofrecer(app, o, Prefs(app).unidad) {}
    }
}
