package com.yammbo.reparto

/**
 * Ordena el envio de la posicion y su retirada al terminar el turno.
 *
 * 🚨 El POST de la posicion y el DELETE de onDestroy van por hilos distintos.
 * Un POST que ya estaba viajando cuando se toco "Terminar turno" puede llegar
 * al servidor DESPUES del DELETE, y entonces quien ya se fue a casa sigue
 * constando como disponible unos tres minutos, hasta que el punto caduca.
 *
 * Reglas:
 * - Cerrado el turno, no empieza ningun envio nuevo.
 * - Si un envio termina con el turno ya cerrado, quien lo hizo repite el
 *   DELETE: su POST pudo pisar al primero.
 *
 * Sin Android a proposito: se prueba en la JVM (RetiradaTest).
 */
class Retirada {

    private var cerrada = false

    /** @return false si el turno ya se cerro: no hay que publicar nada. */
    @Synchronized
    fun empezarEnvio(): Boolean = !cerrada

    /** @return true si hay que repetir el DELETE (se cerro mientras viajaba). */
    @Synchronized
    fun terminarEnvio(): Boolean = cerrada

    @Synchronized
    fun cerrar() { cerrada = true }

    val estaCerrada: Boolean @Synchronized get() = cerrada
}
