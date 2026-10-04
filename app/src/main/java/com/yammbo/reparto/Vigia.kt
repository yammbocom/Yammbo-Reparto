package com.yammbo.reparto

import android.content.Context

/**
 * En que esta el turno, tal y como lo ve el servicio. Lo mismo que dice la
 * notificacion permanente, pero sin texto: la pantalla lo pinta a su manera.
 */
enum class Turno { APAGADO, BUSCANDO, SIN_ENLACE, SIN_PERMISO, NO_DISPONIBLE, DISPONIBLE, LLEVANDO }

/**
 * Lo que se decide con el interruptor de turno, sin Android: se prueba en la JVM.
 */
object Jornada {

    /** El servicio solo arranca con enlace y con el turno empezado. */
    fun debeArrancar(configurada: Boolean, turnoActivo: Boolean): Boolean =
        configurada && turnoActivo

    /**
     * El estado que se ensena. Con el turno terminado es "fuera de turno"
     * aunque el servicio aun no haya publicado su parada: decir "Disponible"
     * un segundo despues de terminar seria mentir justo en lo que importa.
     */
    fun visible(servicio: Turno, turnoActivo: Boolean): Turno =
        if (!turnoActivo) Turno.APAGADO else servicio

    /** Relleno = en marcha y recibiendo; contorno = todo lo demas. */
    fun lleno(t: Turno): Boolean = t == Turno.DISPONIBLE || t == Turno.LLEVANDO
}

/**
 * Decide cuando salta el cartel.
 *
 * El estado vive en un `object` porque lo comparten el servicio y la pantalla:
 * si el cartel de un pedido ya salio, no puede volver a salir cuando la lista
 * se relee diez segundos despues.
 */
object Vigia {

    /** La pantalla esta delante: no hace falta cartel, ya lo esta viendo. */
    @Volatile var enPrimerPlano: Boolean = false

    /** El ultimo estado que publico el servicio, y cuantos pedidos lleva. */
    @Volatile var turno: Turno = Turno.APAGADO
        private set
    @Volatile var llevando: Int = 0
        private set

    /**
     * Quien quiere enterarse de los cambios (la pantalla que este delante).
     * Se llama desde el hilo del servicio: quien escucha salta al principal.
     */
    @Volatile var alCambiarTurno: (() -> Unit)? = null

    fun ponerTurno(t: Turno, n: Int = 0) {
        val cambio = t != turno || n != llevando
        turno = t
        llevando = n
        if (cambio) alCambiarTurno?.invoke()
    }

    /** Ultima vez que ALGUIEN (pantalla o servicio) trajo datos. */
    @Volatile private var ultimoDato: Long = 0L

    private val ofrecidas = HashSet<String>()
    private val ausencias = HashMap<String, Int>()
    private var primera = true

    fun hayDatosRecientes(ms: Long): Boolean =
        ultimoDato != 0L && System.currentTimeMillis() - ultimoDato < ms

    fun marcarDato() { ultimoDato = System.currentTimeMillis() }

    /**
     * Devuelve los pedidos que MERECE la pena anunciar.
     *
     * - Solo los que no lleva nadie: lo que ya cogio no se le vuelve a ofrecer.
     * - Solo una vez cada uno.
     * - Nunca en la primerisima lectura del proceso: al abrir la app despues de
     *   un rato saldrian de golpe cinco carteles de pedidos que ya estaban ahi.
     */
    @Synchronized
    fun nuevas(d: Datos): List<Oferta> {
        marcarDato()
        val libres = d.libres
        val vistosAhora = libres.map { it.orderId }.toSet()

        // Purgar solo tras tres ausencias seguidas. Una respuesta que llega
        // corta o un pedido que parpadea entre lecturas volveria a anunciarse
        // como nuevo, y el cartel repetido es como se aprende a ignorarlo.
        val fuera = ArrayList<String>()
        for (id in ofrecidas) {
            if (id in vistosAhora) { ausencias[id] = 0; continue }
            val n = (ausencias[id] ?: 0) + 1
            ausencias[id] = n
            if (n >= 3) fuera.add(id)
        }
        fuera.forEach { ofrecidas.remove(it); ausencias.remove(it) }

        val nuevas = libres.filter { it.orderId !in ofrecidas }
        nuevas.forEach { ofrecidas.add(it.orderId); ausencias[it.orderId] = 0 }

        if (primera) { primera = false; return emptyList() }
        return nuevas
    }

    /** Al rechazar o aceptar, deja de considerarse pendiente de anunciar. */
    @Synchronized
    fun olvidar(orderId: String) {
        ofrecidas.add(orderId)
        ausencias[orderId] = 0
    }

    /** Solo para las pruebas: vuelve al estado de recien arrancado. */
    @Synchronized
    fun reiniciar() {
        ofrecidas.clear(); ausencias.clear(); primera = true; ultimoDato = 0L
    }

    /**
     * Sin ubicacion no hay turno, asi que tampoco hay cartel.
     *
     * Es la regla de la app entera: si el movil no dice donde esta, quien lo
     * lleva no esta disponible, y ofrecerle un pedido que no puede aceptar
     * solo sirve para que el pedido se quede parado mas tiempo.
     */
    fun debeAvisar(ctx: Context, d: Datos): Boolean {
        if (!d.disponible) return false
        if (enPrimerPlano) return false
        return Prefs(ctx).encima
    }
}
