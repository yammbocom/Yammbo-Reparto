package com.yammbo.reparto

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Al terminar el turno, ningun POST de posicion puede quedar por detras del
 * DELETE: si no, se sigue constando como disponible hasta que el punto caduca.
 */
class RetiradaTest {

    @Test
    fun `con el turno abierto se publica y no se repite el borrado`() {
        val r = Retirada()
        assertTrue(r.empezarEnvio())
        assertFalse(r.terminarEnvio())
    }

    @Test
    fun `un envio que termina despues de cerrar repite el borrado`() {
        val r = Retirada()
        assertTrue(r.empezarEnvio())
        r.cerrar()                     // "Terminar turno" con el POST en vuelo
        assertTrue("el POST pudo llegar despues del DELETE", r.terminarEnvio())
    }

    @Test
    fun `cerrado el turno no empieza ningun envio`() {
        val r = Retirada()
        r.cerrar()
        assertFalse(r.empezarEnvio())
        assertTrue(r.estaCerrada)
    }

    @Test
    fun `un envio acabado antes de cerrar no pide otro borrado`() {
        val r = Retirada()
        assertTrue(r.empezarEnvio())
        assertFalse(r.terminarEnvio())
        r.cerrar()
        // El DELETE de onDestroy basta: nada viajaba ya.
        assertFalse(r.empezarEnvio())
    }

    @Test
    fun `solo un 2xx da por bueno un enlace`() {
        assertTrue(Api.esValido(200))
        assertTrue(Api.esValido(204))
        assertTrue(Api.esValido(299))
        assertFalse(Api.esValido(301))
        assertFalse(Api.esValido(404))
        assertFalse(Api.esValido(500))
        assertFalse("sin red", Api.esValido(-1))
    }
}
