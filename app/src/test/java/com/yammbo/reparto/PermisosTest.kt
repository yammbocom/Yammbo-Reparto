package com.yammbo.reparto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * El fallo que cerraba la app en el PRIMER arranque, y solo en el primero.
 *
 * Android admite **una** peticion de permisos a la vez. La segunda no falla de
 * forma visible: el framework la descarta y llama a onRequestPermissionsResult
 * EN EL ACTO con arrays vacios (Activity.java: "Can request only one set of
 * permissions at a time"). Si ese callback vuelve a pedir, la llamada se
 * descarta otra vez, que llama otra vez... hasta desbordar la pila.
 *
 * Se pedian notificaciones en onCreate y ubicacion en onResume. A la segunda
 * apertura ya no chocaban y todo parecia bien, que es lo que lo hacia dificil
 * de ver.
 *
 * Esto se comprueba sobre el CODIGO FUENTE porque la propiedad es estructural,
 * no de ejecucion: no hay entrada que darle a una funcion para provocarlo, hay
 * una forma de escribir la pantalla que lo provoca. Un test de Robolectric
 * tampoco reproduce el `mHasCurrentPermissionsRequest` del framework real.
 */
class PermisosTest {

    private val fuente: String by lazy {
        val f = File("src/main/java/com/yammbo/reparto/MainActivity.kt")
        assertTrue("no encuentro MainActivity.kt desde " + File(".").absolutePath, f.exists())
        f.readText()
    }

    @Test
    fun `un unico sitio donde se piden permisos`() {
        val n = Regex("ActivityCompat\\.requestPermissions\\(").findAll(fuente).count()
        assertEquals(
            "Debe haber UNA sola llamada a requestPermissions: dos peticiones a la vez " +
                "hacen que el sistema descarte la segunda y conteste al instante.",
            1, n,
        )
    }

    @Test
    fun `el pestillo de permisos no se abre nunca`() {
        // `yaPedidos = false` solo puede aparecer en la declaracion. Reponerlo
        // en cualquier otro sitio (y sobre todo en el callback) devuelve el
        // bucle infinito.
        val n = Regex("yaPedidos = false").findAll(fuente).count()
        assertEquals(
            "yaPedidos es un pestillo de un solo uso: solo se pone a false al declararlo.",
            1, n,
        )
    }

    @Test
    fun `del callback de permisos no se vuelve a pedir`() {
        val i = fuente.indexOf("override fun onRequestPermissionsResult")
        assertTrue("no encuentro onRequestPermissionsResult", i > 0)
        // Hasta la siguiente declaracion de nivel de clase.
        val resto = fuente.substring(i)
        val fin = resto.indexOf("\n    private fun ").let { if (it < 0) resto.length else it }
        val cuerpo = resto.substring(0, fin)
        assertTrue(
            "onRequestPermissionsResult no puede pedir permisos: el sistema contesta " +
                "sincronamente cuando descarta una peticion y eso es una recursion.",
            !cuerpo.contains("pedirPermisos(") && !cuerpo.contains("requestPermissions(this"),
        )
    }

    @Test
    fun `las notificaciones van en la misma peticion que la ubicacion`() {
        // Si vuelve a existir una funcion aparte para pedirlas, vuelven las dos
        // peticiones solapadas.
        assertTrue(
            "no puede haber una peticion de notificaciones por separado",
            !fuente.contains("fun pedirNotificaciones"),
        )
        assertTrue(
            "permisosQueFaltan tiene que incluir POST_NOTIFICATIONS",
            fuente.contains("POST_NOTIFICATIONS"),
        )
    }
}
