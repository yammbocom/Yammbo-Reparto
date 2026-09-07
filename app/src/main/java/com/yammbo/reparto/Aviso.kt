package com.yammbo.reparto

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

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
        return b.setContentTitle(ctx.getString(R.string.app_name))
            .setContentText(texto)
            .setSmallIcon(R.drawable.ic_noti)
            .setOngoing(true)
            .setContentIntent(abrir)
            .build()
    }

    fun notificar(ctx: Context, titulo: String, cuerpo: String) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        val abrir = PendingIntent.getActivity(
            ctx, 1, Intent(ctx, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(ctx, CANAL_OFERTAS)
        else @Suppress("DEPRECATION") Notification.Builder(ctx).setPriority(Notification.PRIORITY_HIGH)
        nm.notify(
            ID_OFERTA,
            b.setContentTitle(titulo)
                .setContentText(cuerpo)
                .setStyle(Notification.BigTextStyle().bigText(cuerpo))
                .setSmallIcon(R.drawable.ic_noti)
                .setAutoCancel(true)
                .setContentIntent(abrir)
                .setFullScreenIntent(abrir, true)
                .build(),
        )
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

    private const val GRIS = "#9E9E9E"
    private const val GRIS_CLARO = "#C7C7C7"
    private const val AMBAR = "#FBBF24"

    /**
     * El cartel con la decision.
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

                fun linea(t: String, sp: Float, color: Int, arriba: Int, negrita: Boolean = false) =
                    TextView(ctx).apply {
                        text = t
                        setTextColor(color)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
                        setPadding(0, arriba, 0, 0)
                        if (negrita) typeface = android.graphics.Typeface.DEFAULT_BOLD
                    }

                fun boton(t: String, relleno: Boolean, alTocar: () -> Unit) = TextView(ctx).apply {
                    text = t
                    gravity = Gravity.CENTER
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                    setTextColor(if (relleno) Color.BLACK else Color.WHITE)
                    setPadding(0, 40, 0, 40)
                    background = GradientDrawable().apply {
                        setColor(if (relleno) Color.WHITE else Color.TRANSPARENT)
                        setStroke(3, if (relleno) Color.WHITE else Color.parseColor("#4A4A4A"))
                        cornerRadius = 999f
                    }
                    isClickable = true
                    setOnClickListener { alTocar() }
                }

                val tarjeta = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    background = GradientDrawable().apply {
                        setColor(Color.BLACK)
                        setStroke(4, Color.WHITE)
                        cornerRadius = 34f
                    }
                    setPadding(56, 46, 56, 40)

                    addView(linea(ctx.getString(R.string.cartel_encabezado), 12f, Color.parseColor(GRIS), 0).apply {
                        letterSpacing = 0.18f
                    })
                    addView(linea(o.clave, 26f, Color.WHITE, 8, negrita = true))
                    // La distancia es lo que decide un si o un no, asi que va
                    // arriba y en grande, no escondida en el detalle.
                    Reparto.lineaDistancia(o, ultimaLat, ultimaLng, unidad, t)?.let {
                        addView(linea(it, 19f, Color.WHITE, 10, negrita = true))
                    }
                    addView(linea(o.direccion.ifBlank { t.sinDireccion }, 15f, Color.parseColor(GRIS_CLARO), 8))
                    if (o.cliente.isNotBlank()) {
                        addView(linea(o.cliente, 14f, Color.parseColor(GRIS), 4))
                    }

                    addView(View(ctx).apply {
                        setBackgroundColor(Color.parseColor("#3A3A3A"))
                        layoutParams = LinearLayout.LayoutParams(-1, 2).apply { topMargin = 22 }
                    })
                    Reparto.detalle(o, t).forEachIndexed { i, d ->
                        val sangrada = d.startsWith(" ")
                        addView(
                            linea(
                                d.trim(),
                                if (sangrada) 13f else 16f,
                                if (sangrada) Color.parseColor(GRIS) else Color.WHITE,
                                if (i == 0) 20 else if (sangrada) 2 else 10,
                                negrita = !sangrada,
                            )
                        )
                    }
                    addView(
                        linea(
                            Reparto.lineaCobro(o, t), 15f,
                            if (o.pagadoOnline) Color.parseColor("#34D399") else Color.parseColor(AMBAR),
                            22, negrita = true,
                        )
                    )

                    addView(LinearLayout(ctx).apply {
                        orientation = LinearLayout.HORIZONTAL
                        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = 34 }
                        addView(
                            boton(ctx.getString(R.string.cartel_rechazar), false) { decidir(ctx, o, "rechazar", alDecidir) },
                            LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = 16 },
                        )
                        addView(
                            boton(ctx.getString(R.string.cartel_aceptar), true) { decidir(ctx, o, "tomar", alDecidir) },
                            LinearLayout.LayoutParams(0, -2, 1.2f),
                        )
                    })
                }

                // Marco a pantalla completa PERO sin cerrar al tocar fuera: este
                // cartel pide una decision, y descartarlo con el pulgar sin
                // querer deja el pedido esperando sin que nadie lo sepa.
                val marco = FrameLayout(ctx).apply {
                    setPadding(44, 0, 44, 0)
                    addView(tarjeta, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
                    isClickable = true
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

    /** Dispara el aviso completo para probarlo sin esperar a un pedido real. */
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
            cobrar = 23.4, moneda = "USD", metodoPago = "cash_on_pickup",
            millasLocal = 2.31, mio = false,
            articulos = listOf("2  Tacos al pastor", "1  Horchata"),
        )
        notificar(app, app.getString(R.string.aviso_titulo, o.clave), o.direccion)
        sonar(app)
        despertar(app)
        return ofrecer(app, o, Prefs(app).unidad) {}
    }
}
