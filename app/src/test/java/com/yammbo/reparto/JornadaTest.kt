package com.yammbo.reparto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * El interruptor de turno y la divulgacion de la ubicacion.
 */
class JornadaTest {

    @Test
    fun `sin turno o sin enlace no se arranca el servicio`() {
        assertTrue(Jornada.debeArrancar(configurada = true, turnoActivo = true))
        assertFalse(Jornada.debeArrancar(configurada = true, turnoActivo = false))
        assertFalse(Jornada.debeArrancar(configurada = false, turnoActivo = true))
        assertFalse(Jornada.debeArrancar(configurada = false, turnoActivo = false))
    }

    @Test
    fun `con el turno terminado se ve fuera de turno aunque el servicio diga otra cosa`() {
        // El servicio publica su parada en onDestroy, un instante despues de
        // tocar Terminar. Entretanto la pantalla no puede decir "Disponible".
        for (t in Turno.values()) {
            assertEquals(Turno.APAGADO, Jornada.visible(t, turnoActivo = false))
        }
    }

    @Test
    fun `con el turno empezado se ve lo que dice el servicio`() {
        for (t in Turno.values()) {
            assertEquals(t, Jornada.visible(t, turnoActivo = true))
        }
    }

    @Test
    fun `solo disponible y llevando van rellenos`() {
        val rellenos = Turno.values().filter { Jornada.lleno(it) }.toSet()
        assertEquals(setOf(Turno.DISPONIBLE, Turno.LLEVANDO), rellenos)
    }

    /**
     * Divulgacion destacada (politica de ubicacion de Google Play): el dialogo
     * del sistema solo lo abre el boton de la pantalla de ubicacion, despues
     * de leer para que se usa. Si vuelve una llamada automatica al pintar la
     * puerta, aqui habria dos sitios que llaman a pedirPermisos().
     */
    @Test
    fun `los permisos solo se piden desde el boton`() {
        val f = File("src/main/java/com/yammbo/reparto/MainActivity.kt")
        assertTrue("no encuentro MainActivity.kt desde " + File(".").absolutePath, f.exists())
        val llamadas = Regex("(?<!fun )pedirPermisos\\(\\)").findAll(f.readText()).count()
        assertEquals(
            "pedirPermisos() solo puede llamarse desde el boton de la pantalla de ubicacion",
            1, llamadas,
        )
    }
}
