package com.yammbo.reparto

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Ajustes. Se abren solos la primera vez, porque sin el enlace la app no puede
 * hacer nada, y ese enlace solo lo tiene el encargado.
 */
class AjustesActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var url: EditText
    private lateinit var sonido: CheckBox
    private lateinit var encima: CheckBox
    private lateinit var estadoEncima: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        prefs = Prefs(this)
        title = getString(R.string.ajustes_app)

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            setPadding(dp(20), dp(20), dp(20), dp(36))
        }
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(col)
        }
        setContentView(scroll)
        insets(scroll)

        col.addView(titulo("Enlace de reparto"))
        col.addView(
            nota(
                "Te lo da el encargado desde el panel, en Reparto. Ese enlace ES tu llave: " +
                    "no pide contraseña y solo ve los pedidos de tu local."
            )
        )
        url = EditText(this).apply {
            setText(prefs.url)
            hint = "https://pos.yammbo.com/repartidor/…"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#6A6A6A"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setSingleLine()
        }
        col.addView(url)

        col.addView(titulo("Avisos"))
        sonido = casilla("Sonido de alarma al entrar un pedido", prefs.sonido)
        col.addView(sonido)
        encima = casilla("Cartel encima de otras apps", prefs.encima)
        col.addView(encima)
        estadoEncima = nota("")
        col.addView(estadoEncima)
        col.addView(boton("Dar permiso para el cartel") { Aviso.pedirPermisoEncima(this) })
        col.addView(boton("Probar el aviso") {
            if (!Aviso.probar(this)) {
                Toast.makeText(this, "Falta el permiso para dibujar encima", Toast.LENGTH_LONG).show()
            }
        })

        col.addView(titulo("Ubicación"))
        col.addView(
            nota(
                "Obligatoria. Se comparte mientras tienes la app abierta y es lo que te " +
                    "mantiene disponible: si se apaga, dejas de recibir pedidos. Tus clientes " +
                    "solo ven tu punto mientras llevas SU pedido, y nunca tu teléfono."
            )
        )

        col.addView(titulo("Versión"))
        col.addView(
            nota(
                "Instalada: " + Actualizador.nombreInstalado(this) +
                    " (" + Actualizador.instalada(this) + ")"
            )
        )
        col.addView(boton("Buscar actualización") { buscarActualizacion() })

        col.addView(boton("Guardar", relleno = true) { guardar() })
    }

    override fun onResume() {
        super.onResume()
        val ok = Aviso.puedeDibujarEncima(this)
        estadoEncima.text = if (ok)
            "Permiso concedido: el cartel puede salir sobre cualquier app."
        else
            "SIN permiso. Sin él llega la notificación, pero no el cartel con Aceptar y Rechazar."
        estadoEncima.setTextColor(Color.parseColor(if (ok) "#34D399" else "#FBBF24"))
    }

    private fun guardar() {
        val v = url.text.toString().trim()
        if (v.isNotEmpty() && !(v.startsWith("https://") && v.contains("/repartidor/"))) {
            Toast.makeText(
                this,
                "Ese no es un enlace de reparto. Tiene que empezar por https:// y llevar /repartidor/",
                Toast.LENGTH_LONG,
            ).show()
            return
        }
        prefs.url = v
        prefs.sonido = sonido.isChecked
        prefs.encima = encima.isChecked
        if (prefs.configurada) ServicioReparto.arrancar(this)
        Toast.makeText(this, "Guardado", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun buscarActualizacion() {
        Toast.makeText(this, "Mirando…", Toast.LENGTH_SHORT).show()
        Thread {
            val v = Actualizador.ultima()
            runOnUiThread {
                when {
                    v == null -> Toast.makeText(this, "No se pudo comprobar", Toast.LENGTH_LONG).show()
                    !Actualizador.hayNueva(this, v) ->
                        Toast.makeText(this, "Ya tienes la última (" + v.name + ")", Toast.LENGTH_LONG).show()
                    else -> {
                        Toast.makeText(this, "Descargando " + v.name + "…", Toast.LENGTH_SHORT).show()
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

    // ── piezas ──────────────────────────────────────────────────────────────

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt().coerceAtLeast(1)

    private fun insets(v: View) {
        ViewCompat.setOnApplyWindowInsetsListener(v) { vista, ins ->
            val b = ins.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            vista.setPadding(b.left, b.top, b.right, b.bottom)
            ins
        }
    }

    private fun titulo(t: String) = TextView(this).apply {
        text = t
        setTextColor(Color.WHITE)
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
        setPadding(0, dp(24), 0, dp(6))
    }

    private fun nota(t: String) = TextView(this).apply {
        text = t
        setTextColor(Color.parseColor("#9E9E9E"))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setPadding(0, 0, 0, dp(8))
    }

    private fun casilla(t: String, marcada: Boolean) = CheckBox(this).apply {
        text = t
        isChecked = marcada
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
    }

    private fun boton(t: String, relleno: Boolean = false, alTocar: () -> Unit) =
        TextView(this).apply {
            text = t
            gravity = android.view.Gravity.CENTER
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(if (relleno) Color.BLACK else Color.WHITE)
            setPadding(dp(18), dp(14), dp(18), dp(14))
            background = GradientDrawable().apply {
                setColor(if (relleno) Color.WHITE else Color.TRANSPARENT)
                setStroke(dp(1), if (relleno) Color.WHITE else Color.parseColor("#3A3A3A"))
                cornerRadius = 999f
            }
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) }
            setOnClickListener { alTocar() }
        }
}
