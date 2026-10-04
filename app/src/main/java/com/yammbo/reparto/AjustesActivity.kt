package com.yammbo.reparto

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged

/**
 * Ajustes, en tarjetas: turno, avisos, permisos y la app.
 *
 * El enlace se pega la primera vez en la pantalla de bienvenida; aqui se cambia
 * despues. Los interruptores se guardan al tocarlos. El enlace no: se valida y
 * se guarda con su boton, porque uno a medio pegar dejaria la app sin turno.
 *
 * 🚨 Desde aqui NO se piden permisos de ejecucion: la unica peticion vive en
 * MainActivity (ver PermisosTest). Los botones de "Arreglar" llevan a los
 * ajustes del sistema.
 */
class AjustesActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var p: Ui.Paleta
    private lateinit var url: EditText
    private lateinit var errorEnlace: TextView
    private lateinit var estado: TextView
    private lateinit var ranuraTurno: FrameLayout
    private lateinit var permisos: LinearLayout

    private val escuchaTurno: () -> Unit = { runOnUiThread { pintarEstado() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        p = Ui.paleta(this)
        title = getString(R.string.ajustes_app)
        Ui.barrasSistema(this, p)

        val raiz = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(p.fondo)
        }
        setContentView(raiz)
        Ui.insets(raiz)

        val listo = Ui.boton(this, p, getString(R.string.aj_listo), Ui.Tipo.TERCIARIO, compacto = true) {
            finish()
        }
        raiz.addView(Ui.barraSuperior(this, p, getString(R.string.ajustes), listo))
        raiz.addView(Ui.separador(this, p))

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(0), dp(16), dp(32))
        }
        raiz.addView(ScrollView(this).apply {
            isFillViewport = true
            addView(col)
        }, LinearLayout.LayoutParams(-1, 0, 1f))

        // ── Turno ──
        col.addView(Ui.seccion(this, p, getString(R.string.aj_turno)))
        val turno = Ui.tarjeta(this, p)
        estado = Ui.chip(this, p, "", lleno = false)
        turno.addView(Ui.fila(this, p, getString(R.string.aj_estado), null, estado))
        ranuraTurno = FrameLayout(this).apply { setPadding(dp(16), 0, dp(16), dp(16)) }
        turno.addView(ranuraTurno, LinearLayout.LayoutParams(-1, -2))
        turno.addView(Ui.separador(this, p, 16))
        turno.addView(bloqueEnlace())
        turno.addView(Ui.separador(this, p, 16))
        turno.addView(Ui.fila(this, p, getString(R.string.aj_ubicacion),
            getString(R.string.aj_ubicacion_nota), null))
        col.addView(turno)

        // ── Avisos ──
        col.addView(Ui.seccion(this, p, getString(R.string.aj_avisos)))
        val avisos = Ui.tarjeta(this, p)
        avisos.addView(filaInterruptor(getString(R.string.aj_sonido), null, prefs.sonido) {
            prefs.sonido = it
        })
        avisos.addView(Ui.separador(this, p, 16))
        avisos.addView(filaInterruptor(getString(R.string.aj_encima),
            getString(R.string.aj_encima_sub), prefs.encima) { prefs.encima = it })
        avisos.addView(Ui.separador(this, p, 16))
        avisos.addView(Ui.fila(this, p, getString(R.string.aj_probar),
            getString(R.string.aj_probar_sub), Ui.chevron(this, p)) {
            if (!Aviso.probar(this)) {
                Toast.makeText(this, R.string.aj_falta_encima, Toast.LENGTH_LONG).show()
            }
        })
        col.addView(avisos)

        // ── Permisos ──
        col.addView(Ui.seccion(this, p, getString(R.string.aj_permisos)))
        permisos = Ui.tarjeta(this, p)
        col.addView(permisos)

        // ── Aplicacion ──
        col.addView(Ui.seccion(this, p, getString(R.string.aj_app)))
        val app = Ui.tarjeta(this, p)
        app.addView(Ui.fila(this, p, getString(R.string.aj_acerca), getString(R.string.aj_acerca_sub), null))
        if (BuildConfig.SELF_UPDATE) {
            app.addView(Ui.separador(this, p, 16))
            app.addView(Ui.fila(this, p, getString(R.string.aj_buscar), null, Ui.chevron(this, p)) {
                buscarActualizacion()
            })
        }
        col.addView(app)

        col.addView(Ui.texto(this,
            getString(R.string.app_name) + " · " +
                getString(R.string.aj_instalada, Actualizador.nombreInstalado(this), Actualizador.instalada(this)),
            Ui.Sp.ETIQUETA, p.texto2).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(32), 0, 0)
        }, LinearLayout.LayoutParams(-1, -2))
    }

    override fun onResume() {
        super.onResume()
        Vigia.alCambiarTurno = escuchaTurno
        pintarEstado()
        pintarPermisos()
    }

    override fun onPause() {
        super.onPause()
        if (Vigia.alCambiarTurno === escuchaTurno) Vigia.alCambiarTurno = null
    }

    // ── turno ───────────────────────────────────────────────────────────────

    private fun pintarEstado() {
        val activo = prefs.turnoActivo
        val visible = Jornada.visible(Vigia.turno, activo)
        val t = when (visible) {
            Turno.DISPONIBLE -> getString(R.string.estado_disponible)
            Turno.LLEVANDO -> getString(R.string.estado_llevando, Vigia.llevando)
            Turno.NO_DISPONIBLE -> getString(R.string.chip_no_disponible)
            Turno.BUSCANDO -> getString(R.string.chip_buscando)
            Turno.SIN_ENLACE -> getString(R.string.chip_sin_enlace)
            Turno.SIN_PERMISO -> getString(R.string.chip_sin_permiso)
            Turno.APAGADO -> getString(R.string.chip_apagado)
        }
        Ui.pintarChip(estado, p, t, Jornada.lleno(visible))

        // Empezar relleno (es lo que se busca), terminar con contorno.
        ranuraTurno.removeAllViews()
        ranuraTurno.addView(
            if (activo) Ui.boton(this, p, getString(R.string.turno_terminar), Ui.Tipo.SECUNDARIO) {
                confirmarTerminar()
            } else Ui.boton(this, p, getString(R.string.turno_empezar), Ui.Tipo.PRIMARIO) {
                ServicioReparto.empezarTurno(this)
                pintarEstado()
            },
            FrameLayout.LayoutParams(-1, -2),
        )
    }

    /** Igual que en la pantalla principal: se confirma antes de dejar de recibir pedidos. */
    private fun confirmarTerminar() {
        if (isFinishing) return
        AlertDialog.Builder(this)
            .setTitle(R.string.turno_confirmar_titulo)
            .setMessage(R.string.turno_confirmar_texto)
            .setNegativeButton(R.string.turno_cancelar, null)
            .setPositiveButton(R.string.turno_terminar) { _, _ ->
                ServicioReparto.terminarTurno(this)
                pintarEstado()
            }
            .show()
    }

    private fun bloqueEnlace(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(16), dp(16), dp(16))
        addView(Ui.texto(this@AjustesActivity, getString(R.string.aj_enlace), Ui.Sp.CUERPO, p.texto))
        addView(Ui.texto(this@AjustesActivity, getString(R.string.aj_enlace_nota), Ui.Sp.SECUNDARIO, p.texto2),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2); bottomMargin = dp(12) })
        url = Ui.campo(this@AjustesActivity, p, "https://pos.yammbo.com/repartidor/…", p.fondo).apply {
            setText(prefs.url)
            contentDescription = getString(R.string.aj_enlace)
        }
        addView(url)
        errorEnlace = Ui.texto(this@AjustesActivity, getString(R.string.aj_enlace_malo),
            Ui.Sp.SECUNDARIO, p.fondo, medio = true).apply {
            // Invertido: lo que pide atencion se dice con contraste, no con color.
            background = Ui.forma(this@AjustesActivity, p.texto, 12f)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            visibility = View.GONE
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        addView(errorEnlace, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        url.doAfterTextChanged { errorEnlace.visibility = View.GONE }
        addView(Ui.boton(this@AjustesActivity, p, getString(R.string.aj_guardar), Ui.Tipo.SECUNDARIO) {
            guardar()
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
    }

    private fun guardar() {
        val v = url.text.toString().trim()
        if (v.isNotEmpty() && !(v.startsWith("https://") && v.contains("/repartidor/"))) {
            errorEnlace.visibility = View.VISIBLE
            Toast.makeText(this, R.string.aj_enlace_malo, Toast.LENGTH_LONG).show()
            return
        }
        prefs.url = v
        if (Jornada.debeArrancar(prefs.configurada, prefs.turnoActivo)) ServicioReparto.arrancar(this)
        Toast.makeText(this, R.string.aj_guardado, Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun filaInterruptor(
        titulo: String, subtitulo: String?, marcado: Boolean, alCambiar: (Boolean) -> Unit,
    ): View {
        val sw = Ui.interruptor(this, p, marcado, alCambiar).apply { contentDescription = titulo }
        // Toda la fila conmuta, no solo el interruptor: es una diana mas grande.
        return Ui.fila(this, p, titulo, subtitulo, sw) { sw.toggle() }
    }

    // ── permisos ────────────────────────────────────────────────────────────

    /** Se repinta al volver: el permiso se concede fuera, en los ajustes del sistema. */
    private fun pintarPermisos() {
        permisos.removeAllViews()

        val ubicacion =
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        permisos.addView(filaPermiso(getString(R.string.aj_ubicacion),
            getString(if (ubicacion) R.string.perm_ok else R.string.perm_falta), ubicacion) {
            abrir(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.parse("package:" + packageName)))
        })

        permisos.addView(Ui.separador(this, p, 16))
        val gps = runCatching {
            val lm = getSystemService(LOCATION_SERVICE) as LocationManager
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        }.getOrDefault(false)
        permisos.addView(filaPermiso(getString(R.string.perm_gps),
            getString(if (gps) R.string.perm_gps_on else R.string.perm_gps_off), gps) {
            abrir(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
        })

        permisos.addView(Ui.separador(this, p, 16))
        val notis = NotificationManagerCompat.from(this).areNotificationsEnabled()
        permisos.addView(filaPermiso(getString(R.string.perm_notis),
            getString(if (notis) R.string.perm_ok else R.string.perm_falta), notis) {
            abrir(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                else Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.parse("package:" + packageName))
            )
        })

        permisos.addView(Ui.separador(this, p, 16))
        val encima = Aviso.puedeDibujarEncima(this)
        permisos.addView(filaPermiso(getString(R.string.aj_encima),
            getString(if (encima) R.string.aj_encima_ok else R.string.aj_encima_no), encima) {
            Aviso.pedirPermisoEncima(this)
        })
    }

    /** Concedido: solo el estado. Falta: el estado y un boton para arreglarlo. */
    private fun filaPermiso(titulo: String, sub: String, ok: Boolean, arreglar: () -> Unit): View {
        val fin = if (ok) null
        else Ui.boton(this, p, getString(R.string.perm_arreglar), Ui.Tipo.PRIMARIO, compacto = true) {
            arreglar()
        }.apply { contentDescription = getString(R.string.perm_arreglar) + ": " + titulo }
        return Ui.fila(this, p, titulo, sub, fin)
    }

    private fun abrir(i: Intent) {
        runCatching { startActivity(i) }
    }

    // ── actualizacion ───────────────────────────────────────────────────────

    private fun buscarActualizacion() {
        Toast.makeText(this, R.string.act_mirando, Toast.LENGTH_SHORT).show()
        Thread {
            val v = Actualizador.ultima()
            runOnUiThread {
                when {
                    v == null -> Toast.makeText(this, R.string.act_sin_comprobar, Toast.LENGTH_LONG).show()
                    !Actualizador.hayNueva(this, v) ->
                        Toast.makeText(this, getString(R.string.act_al_dia, v.name), Toast.LENGTH_LONG).show()
                    else -> {
                        Toast.makeText(this, getString(R.string.act_descargando, v.name), Toast.LENGTH_SHORT).show()
                        Thread {
                            val e = Actualizador.descargarEInstalar(this, v)
                            if (e != null) runOnUiThread {
                                Toast.makeText(this, e, Toast.LENGTH_LONG).show()
                            }
                        }.start()
                    }
                }
            }
        }.start()
    }

    private fun dp(v: Int) = Ui.dp(this, v)
}
