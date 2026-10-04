package com.yammbo.reparto

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Todo el aspecto de la app en un solo sitio: colores, medidas, tipos y piezas.
 *
 * B/N estricto, como el resto de Yammbo y como la app de cocina. Ningun tono,
 * ni siquiera un gris azulado: los estados se dicen con peso, relleno e
 * inversion, nunca con color. Un "verde = bien" no se lee al sol con gafas de
 * sol, y un rojo de marca no existe.
 *
 * Las pantallas no escriben un color ni un dp a mano: piden una [Paleta] y
 * construyen con estas piezas. Asi el modo claro y el oscuro no pueden
 * divergir pantalla a pantalla.
 */
object Ui {

    /** Colores de un modo. `oscura` decide el contraste de las barras del sistema. */
    class Paleta(
        val fondo: Int,
        val texto: Int,
        val texto2: Int,
        val linea: Int,
        val superficie: Int,
        val oscura: Boolean,
    )

    val CLARA = Paleta(
        fondo = 0xFFFFFFFF.toInt(),
        texto = 0xFF0B0B0B.toInt(),
        texto2 = 0xFF5F5F5F.toInt(),
        linea = 0xFFE6E6E6.toInt(),
        superficie = 0xFFF4F4F4.toInt(),
        oscura = false,
    )

    val OSCURA = Paleta(
        fondo = 0xFF0B0B0B.toInt(),
        texto = 0xFFF2F2F2.toInt(),
        texto2 = 0xFFA3A3A3.toInt(),
        linea = 0xFF262626.toInt(),
        superficie = 0xFF161616.toInt(),
        oscura = true,
    )

    fun oscuro(ctx: Context): Boolean =
        (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    /** La del sistema: sigue el modo claro/oscuro del movil. */
    fun paleta(ctx: Context): Paleta = if (oscuro(ctx)) OSCURA else CLARA

    /** La contraria: para lo que pide atencion (el cartel de un pedido). */
    fun invertida(ctx: Context): Paleta = if (oscuro(ctx)) CLARA else OSCURA

    // ── medidas ─────────────────────────────────────────────────────────────

    fun dp(ctx: Context, v: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, ctx.resources.displayMetrics)
            .toInt().coerceAtLeast(if (v > 0f) 1 else 0)

    fun dp(ctx: Context, v: Int): Int = dp(ctx, v.toFloat())

    /** Margen lateral de las pantallas. */
    const val MARGEN = 20

    // ── tipos ───────────────────────────────────────────────────────────────

    /** Escala de la app, en sp para respetar el tamano de letra del movil. */
    object Sp {
        const val TITULO_GRANDE = 28f
        const val TITULO = 20f
        const val CUERPO = 16f
        const val SECUNDARIO = 14f
        const val ETIQUETA = 12f
    }

    val NORMAL: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    val MEDIO: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    /** Solo para lo que no se puede pasar por alto: el importe a cobrar. */
    val NEGRITA: Typeface = Typeface.create("sans-serif", Typeface.BOLD)

    fun texto(
        ctx: Context, t: CharSequence, sp: Float, color: Int, medio: Boolean = false,
    ): TextView = TextView(ctx).apply {
        text = t
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        typeface = if (medio) MEDIO else NORMAL
        // Interlineado algo mas holgado que el de serie: se lee de reojo.
        setLineSpacing(0f, 1.15f)
    }

    /** Etiqueta en versalitas: "PASO 2 DE 3", "PEDIDO PARA REPARTIR". */
    fun etiqueta(ctx: Context, t: CharSequence, color: Int): TextView =
        texto(ctx, t, Sp.ETIQUETA, color, medio = true).apply {
            isAllCaps = true
            letterSpacing = 0.12f
        }

    // ── fondos ──────────────────────────────────────────────────────────────

    fun forma(ctx: Context, relleno: Int, radioDp: Float, borde: Int? = null, bordeDp: Float = 1.5f) =
        GradientDrawable().apply {
            setColor(relleno)
            cornerRadius = dp(ctx, radioDp).toFloat()
            if (borde != null) setStroke(dp(ctx, bordeDp), borde)
        }

    /** Pulsacion visible sin color: una sombra del propio texto. */
    private fun ondaColor(color: Int): ColorStateList =
        ColorStateList.valueOf(Color.argb(0x33, Color.red(color), Color.green(color), Color.blue(color)))

    fun conOnda(ctx: Context, fondo: Drawable?, color: Int, radioDp: Float): Drawable {
        val mascara = forma(ctx, Color.WHITE, radioDp)
        return RippleDrawable(ondaColor(color), fondo, mascara)
    }

    /** Onda sin bordes, para botones de solo icono. */
    fun ondaSinBorde(color: Int): Drawable = RippleDrawable(ondaColor(color), null, null)

    // ── barras del sistema ──────────────────────────────────────────────────

    /**
     * De borde a borde (obligatorio con targetSdk 35+) y con los iconos de la
     * barra de estado en el contraste que toca: oscuros sobre blanco, claros
     * sobre negro.
     */
    @Suppress("DEPRECATION")
    fun barrasSistema(a: Activity, p: Paleta) {
        val w = a.window
        WindowCompat.setDecorFitsSystemWindows(w, false)
        w.setBackgroundDrawable(ColorDrawable(p.fondo))
        w.statusBarColor = Color.TRANSPARENT
        // Antes de Android 8 no hay iconos oscuros en la barra de navegacion:
        // transparente sobre blanco serian iconos blancos sobre blanco.
        w.navigationBarColor =
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O && !p.oscura) Color.BLACK
            else Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) w.isNavigationBarContrastEnforced = false
        WindowInsetsControllerCompat(w, w.decorView).apply {
            isAppearanceLightStatusBars = !p.oscura
            isAppearanceLightNavigationBars = !p.oscura
        }
    }

    /**
     * Los insets van al CONTENEDOR, no al WebView: puestos en el WebView, la
     * pagina se dibujaba igual debajo de la barra de estado. El teclado cuenta
     * como inset de abajo para que el campo del enlace no quede tapado.
     */
    fun insets(v: View) {
        ViewCompat.setOnApplyWindowInsetsListener(v) { vista, ins ->
            val b = ins.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val teclado = ins.getInsets(WindowInsetsCompat.Type.ime()).bottom
            vista.setPadding(b.left, b.top, b.right, maxOf(b.bottom, teclado))
            WindowInsetsCompat.CONSUMED
        }
    }

    // ── piezas ──────────────────────────────────────────────────────────────

    /** El glifo de la app (el pin con su ruta) en una loseta invertida. */
    fun logo(ctx: Context, p: Paleta, ladoDp: Int): View = FrameLayout(ctx).apply {
        background = forma(ctx, p.texto, ladoDp * 0.28f)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        addView(
            icono(ctx, R.drawable.ic_noti, p.fondo),
            FrameLayout.LayoutParams(dp(ctx, ladoDp * 0.62f), dp(ctx, ladoDp * 0.62f), Gravity.CENTER),
        )
        layoutParams = LinearLayout.LayoutParams(dp(ctx, ladoDp), dp(ctx, ladoDp))
    }

    fun icono(ctx: Context, res: Int, color: Int): ImageView = ImageView(ctx).apply {
        setImageResource(res)
        imageTintList = ColorStateList.valueOf(color)
        scaleType = ImageView.ScaleType.FIT_CENTER
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /** Boton de solo icono: 48dp de diana y su descripcion para TalkBack. */
    fun botonIcono(ctx: Context, p: Paleta, res: Int, descripcion: String, alTocar: () -> Unit) =
        ImageView(ctx).apply {
            setImageResource(res)
            imageTintList = ColorStateList.valueOf(p.texto)
            scaleType = ImageView.ScaleType.CENTER
            contentDescription = descripcion
            background = ondaSinBorde(p.texto)
            isClickable = true
            isFocusable = true
            setOnClickListener { alTocar() }
            layoutParams = LinearLayout.LayoutParams(dp(ctx, 48), dp(ctx, 48))
        }

    /** Barra de arriba: logo, nombre y una accion al final. */
    fun barraSuperior(ctx: Context, p: Paleta, titulo: String, accion: View?): LinearLayout =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(ctx, 64)
            setPadding(dp(ctx, MARGEN), dp(ctx, 8), dp(ctx, 8), dp(ctx, 8))
            addView(logo(ctx, p, 32))
            addView(
                texto(ctx, titulo, Sp.TITULO, p.texto, medio = true).apply {
                    isSingleLine = true
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    // Es el titulo de la pantalla para TalkBack.
                    ViewCompat.setAccessibilityHeading(this, true)
                },
                LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(ctx, 12) },
            )
            if (accion != null) addView(accion)
        }

    fun separador(ctx: Context, p: Paleta, sangriaDp: Int = 0): View = View(ctx).apply {
        setBackgroundColor(p.linea)
        layoutParams = LinearLayout.LayoutParams(-1, dp(ctx, 1)).apply {
            marginStart = dp(ctx, sangriaDp)
        }
    }

    fun seccion(ctx: Context, p: Paleta, t: String): TextView =
        texto(ctx, t, Sp.SECUNDARIO, p.texto2, medio = true).apply {
            setPadding(dp(ctx, 4), dp(ctx, 24), dp(ctx, 4), dp(ctx, 8))
            ViewCompat.setAccessibilityHeading(this, true)
        }

    /** Tarjeta de superficie: agrupa filas. Radio 16dp, sin borde ni sombra. */
    fun tarjeta(ctx: Context, p: Paleta): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = forma(ctx, p.superficie, 16f)
        clipToOutline = true
        layoutParams = LinearLayout.LayoutParams(-1, -2)
    }

    /**
     * Fila de ajustes: titulo, subtitulo opcional y algo al final (un
     * interruptor, un valor, un chevron o un boton).
     */
    fun fila(
        ctx: Context, p: Paleta, titulo: String, subtitulo: CharSequence?,
        fin: View?, alTocar: (() -> Unit)? = null,
    ): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(ctx, 64)
        setPadding(dp(ctx, 16), dp(ctx, 12), dp(ctx, 16), dp(ctx, 12))
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(texto(ctx, titulo, Sp.CUERPO, p.texto))
            if (!subtitulo.isNullOrBlank()) {
                addView(texto(ctx, subtitulo, Sp.SECUNDARIO, p.texto2).apply {
                    setPadding(0, dp(ctx, 2), 0, 0)
                })
            }
        }
        addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        if (fin != null) {
            addView(fin, (fin.layoutParams as? LinearLayout.LayoutParams
                ?: LinearLayout.LayoutParams(-2, -2)).apply { marginStart = dp(ctx, 16) })
        }
        if (alTocar != null) {
            background = conOnda(ctx, null, p.texto, 0f)
            isClickable = true
            isFocusable = true
            setOnClickListener { alTocar() }
        }
        layoutParams = LinearLayout.LayoutParams(-1, -2)
    }

    fun valor(ctx: Context, p: Paleta, t: String): TextView =
        texto(ctx, t, Sp.SECUNDARIO, p.texto2)

    fun chevron(ctx: Context, p: Paleta): ImageView = icono(ctx, R.drawable.ic_chevron, p.texto2).apply {
        layoutParams = LinearLayout.LayoutParams(dp(ctx, 24), dp(ctx, 24))
    }

    fun interruptor(ctx: Context, p: Paleta, marcado: Boolean, alCambiar: (Boolean) -> Unit) =
        SwitchCompat(ctx).apply {
            isChecked = marcado
            val estados = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
            thumbTintList = ColorStateList(estados, intArrayOf(p.fondo, p.texto2))
            trackTintList = ColorStateList(estados, intArrayOf(p.texto, p.linea))
            trackTintMode = android.graphics.PorterDuff.Mode.SRC_IN
            minimumHeight = dp(ctx, 48)
            setOnCheckedChangeListener { _, v -> alCambiar(v) }
        }

    /**
     * Chip de estado. Relleno = disponible / en marcha; solo contorno = parado
     * o pendiente. Nunca un color.
     */
    fun chip(ctx: Context, p: Paleta, t: String, lleno: Boolean, grande: Boolean = false): TextView =
        texto(ctx, t, if (grande) Sp.CUERPO else Sp.SECUNDARIO, p.texto, medio = true).apply {
            gravity = Gravity.CENTER
            isSingleLine = true
            val h = if (grande) 16 else 12
            setPadding(dp(ctx, h), dp(ctx, if (grande) 8 else 5), dp(ctx, h), dp(ctx, if (grande) 8 else 5))
            minimumHeight = dp(ctx, if (grande) 40 else 28)
            pintarChip(this, p, t, lleno)
        }

    fun pintarChip(v: TextView, p: Paleta, t: String, lleno: Boolean) {
        v.text = t
        v.setTextColor(if (lleno) p.fondo else p.texto)
        v.background = forma(v.context, if (lleno) p.texto else Color.TRANSPARENT, 999f,
            borde = p.texto, bordeDp = 1.5f)
    }

    enum class Tipo { PRIMARIO, SECUNDARIO, TERCIARIO }

    /**
     * Botones de 52dp y radio 14dp: primario relleno, secundario con contorno
     * de 1,5dp, terciario solo texto. `compacto` para los de dentro de una
     * fila, que siguen teniendo 48dp de diana.
     */
    fun boton(
        ctx: Context, p: Paleta, t: String, tipo: Tipo, compacto: Boolean = false,
        alTocar: () -> Unit,
    ): TextView = texto(ctx, t, if (compacto) Sp.SECUNDARIO else Sp.CUERPO, p.texto, medio = true).apply {
        gravity = Gravity.CENTER
        minimumHeight = dp(ctx, if (compacto) 48 else 52)
        minimumWidth = dp(ctx, if (compacto) 64 else 88)
        setPadding(dp(ctx, if (compacto) 16 else 20), dp(ctx, 8), dp(ctx, if (compacto) 16 else 20), dp(ctx, 8))
        val (relleno, tinta, borde) = when (tipo) {
            Tipo.PRIMARIO -> Triple(p.texto, p.fondo, null)
            Tipo.SECUNDARIO -> Triple(Color.TRANSPARENT, p.texto, p.texto)
            Tipo.TERCIARIO -> Triple(Color.TRANSPARENT, p.texto, null)
        }
        setTextColor(tinta)
        background = conOnda(ctx, forma(ctx, relleno, 14f, borde), tinta, 14f)
        isClickable = true
        isFocusable = true
        setOnClickListener { if (isEnabled) alTocar() }
    }

    /** Apagar un boton mientras trabaja: se ve y no responde. */
    fun habilitar(v: View, si: Boolean) {
        v.isEnabled = si
        v.alpha = if (si) 1f else 0.5f
    }

    /** Campo de texto con superficie y radio de boton. */
    fun campo(ctx: Context, p: Paleta, pista: String, relleno: Int): EditText = EditText(ctx).apply {
        hint = pista
        setTextColor(p.texto)
        setHintTextColor(p.texto2)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, Sp.CUERPO)
        typeface = NORMAL
        isSingleLine = true
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        imeOptions = EditorInfo.IME_ACTION_DONE
        minimumHeight = dp(ctx, 52)
        setPadding(dp(ctx, 16), dp(ctx, 12), dp(ctx, 16), dp(ctx, 12))
        background = forma(ctx, relleno, 14f, borde = p.linea, bordeDp = 1f)
        layoutParams = LinearLayout.LayoutParams(-1, -2)
    }

    /** Circulo de superficie con un glifo: la ilustracion de una pantalla de paso. */
    fun ilustracion(ctx: Context, p: Paleta, res: Int): View = FrameLayout(ctx).apply {
        background = forma(ctx, p.superficie, 999f)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        addView(icono(ctx, res, p.texto),
            FrameLayout.LayoutParams(dp(ctx, 48), dp(ctx, 48), Gravity.CENTER))
        layoutParams = LinearLayout.LayoutParams(dp(ctx, 112), dp(ctx, 112))
    }

    class Paso(val vista: View, val boton: TextView, val secundario: TextView?)

    /**
     * Pantalla de paso: ilustracion, "Paso 2 de 3", titulo, el motivo en una o
     * dos frases y un boton. Tambien sirve para los errores a pantalla
     * completa (sin `rotulo`).
     *
     * `extra` va entre el motivo y el boton (el campo del enlace, una pista).
     */
    fun paso(
        ctx: Context, p: Paleta, dibujo: View, rotulo: String?,
        titulo: String, razon: String, textoBoton: String, alTocar: () -> Unit,
        extra: View? = null, otro: String? = null, alOtro: (() -> Unit)? = null,
    ): Paso {
        val m = dp(ctx, MARGEN + 4)
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(m, dp(ctx, 24), m, dp(ctx, 24))
        }
        col.addView(View(ctx), LinearLayout.LayoutParams(0, 0, 1f))
        col.addView(dibujo)
        if (rotulo != null) {
            col.addView(etiqueta(ctx, rotulo, p.texto2).apply { gravity = Gravity.CENTER },
                LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(ctx, 32) })
        }
        col.addView(
            texto(ctx, titulo, Sp.TITULO_GRANDE, p.texto, medio = true).apply {
                gravity = Gravity.CENTER
                ViewCompat.setAccessibilityHeading(this, true)
            },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(ctx, if (rotulo != null) 8 else 32) },
        )
        col.addView(
            texto(ctx, razon, Sp.CUERPO, p.texto2).apply { gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(ctx, 12) },
        )
        if (extra != null) {
            col.addView(extra, (extra.layoutParams as? LinearLayout.LayoutParams
                ?: LinearLayout.LayoutParams(-1, -2)).apply { topMargin = dp(ctx, 24) })
        }
        col.addView(View(ctx), LinearLayout.LayoutParams(0, 0, 1f).apply { topMargin = dp(ctx, 32) })
        val b = boton(ctx, p, textoBoton, Tipo.PRIMARIO, alTocar = alTocar)
        col.addView(b, LinearLayout.LayoutParams(-1, -2))
        var b2: TextView? = null
        if (otro != null && alOtro != null) {
            b2 = boton(ctx, p, otro, Tipo.TERCIARIO, alTocar = alOtro)
            col.addView(b2, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(ctx, 8) })
        }
        val scroll = ScrollView(ctx).apply {
            isFillViewport = true
            setBackgroundColor(p.fondo)
            addView(col, FrameLayout.LayoutParams(-1, -2))
        }
        return Paso(scroll, b, b2)
    }
}
