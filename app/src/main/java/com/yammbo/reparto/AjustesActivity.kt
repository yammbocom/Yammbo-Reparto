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

        col.addView(titulo(getString(R.string.aj_enlace)))
        col.addView(
            nota(getString(R.string.aj_enlace_nota))
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

        col.addView(titulo(getString(R.string.aj_avisos)))
        sonido = casilla(getString(R.string.aj_sonido), prefs.sonido)
        col.addView(sonido)
        encima = casilla(getString(R.string.aj_encima), prefs.encima)
        col.addView(encima)
        estadoEncima = nota("")
        col.addView(estadoEncima)
        col.addView(boton(getString(R.string.aj_encima_dar)) { Aviso.pedirPermisoEncima(this) })
        col.addView(boton(getString(R.string.aj_probar)) {
            if (!Aviso.probar(this)) {
                Toast.makeText(this, R.string.aj_falta_encima, Toast.LENGTH_LONG).show()
            }
        })

        col.addView(titulo(getString(R.string.aj_ubicacion)))
        col.addView(
            nota(getString(R.string.aj_ubicacion_nota))
        )

        col.addView(titulo(getString(R.string.aj_version)))
        col.addView(
            nota(getString(R.string.aj_instalada, Actualizador.nombreInstalado(this), Actualizador.instalada(this)))
        )
        col.addView(boton(getString(R.string.aj_buscar)) { buscarActualizacion() })

        col.addView(boton(getString(R.string.aj_guardar), relleno = true) { guardar() })
    }

    override fun onResume() {
        super.onResume()
        val ok = Aviso.puedeDibujarEncima(this)
        estadoEncima.text = getString(if (ok) R.string.aj_encima_ok else R.string.aj_encima_no)
        estadoEncima.setTextColor(Color.parseColor(if (ok) "#34D399" else "#FBBF24"))
    }

    private fun guardar() {
        val v = url.text.toString().trim()
        if (v.isNotEmpty() && !(v.startsWith("https://") && v.contains("/repartidor/"))) {
            Toast.makeText(this, R.string.aj_enlace_malo, Toast.LENGTH_LONG).show()
            return
        }
        prefs.url = v
        prefs.sonido = sonido.isChecked
        prefs.encima = encima.isChecked
        if (prefs.configurada) ServicioReparto.arrancar(this)
        Toast.makeText(this, R.string.aj_guardado, Toast.LENGTH_SHORT).show()
        finish()
    }

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
