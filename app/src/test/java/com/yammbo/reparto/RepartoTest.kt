package com.yammbo.reparto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * El nucleo de la app en la JVM, sin movil.
 *
 * Es lo unico que se puede comprobar sin salir a la calle con el APK, y da la
 * casualidad de que es donde estan los errores caros: una conversion mal hecha
 * manda a alguien a 5 km de donde tiene que ir, y una respuesta mal leida hace
 * desaparecer un pedido que sigue existiendo.
 */
class RepartoTest {

    private fun oferta(
        id: String = "a1",
        lat: Double? = 34.0700, lng: Double? = -118.2600,
        cobrar: Double? = 23.4,
        metodo: String = "cash_on_pickup",
        millasLocal: Double? = 2.31,
        mio: Boolean = false,
        nota: String? = null,
        articulos: List<String> = listOf("2  Tacos", "1  Horchata"),
    ) = Oferta(
        orderId = id, clave = "W-1042", direccion = "1234 W 54th St",
        lat = lat, lng = lng, cliente = "Ana", telefono = "3105550000", nota = nota,
        cobrar = cobrar, moneda = "USD", metodoPago = metodo,
        millasLocal = millasLocal, mio = mio, articulos = articulos,
    )

    // ── haversine ───────────────────────────────────────────────────────────

    @Test
    fun `la distancia coincide con valores conocidos`() {
        // Los Angeles - Nueva York: 2445 millas por el circulo maximo.
        val laNy = Reparto.millas(34.0522, -118.2437, 40.7128, -74.0060)
        assertTrue("LA-NY salio " + laNy, abs(laNy - 2445) < 15)

        // Un grado de latitud son 69,05 millas en cualquier meridiano.
        val grado = Reparto.millas(0.0, 0.0, 1.0, 0.0)
        assertTrue("un grado salio " + grado, abs(grado - 69.05) < 0.2)

        // El mismo punto son cero, no un residuo de coma flotante.
        assertEquals(0.0, Reparto.millas(34.05, -118.24, 34.05, -118.24), 1e-9)
    }

    @Test
    fun `la distancia es simetrica`() {
        val ida = Reparto.millas(34.05, -118.24, 33.96, -118.27)
        val vuelta = Reparto.millas(33.96, -118.27, 34.05, -118.24)
        assertEquals(ida, vuelta, 1e-9)
    }

    // ── como se escribe ─────────────────────────────────────────────────────

    @Test
    fun `millas y kilometros`() {
        assertEquals("1.5 mi", Reparto.distancia(1.5, "mi"))
        assertEquals("12 mi", Reparto.distancia(12.4, "mi"))
        // 2,31 mi son 3,717 km
        assertEquals("3.7 km", Reparto.distancia(2.31, "km"))
        assertEquals("20 km", Reparto.distancia(12.4, "km"))
    }

    @Test
    fun `de cerca se cambia de unidad`() {
        // Decimales de milla no dicen nada a dos manzanas.
        assertEquals("530 ft", Reparto.distancia(0.1, "mi"))
        assertEquals("161 m", Reparto.distancia(0.1, "km"))
    }

    @Test
    fun `sin dato no se inventa una distancia`() {
        assertNull(Reparto.distancia(null, "mi"))
        assertNull(Reparto.distancia(Double.NaN, "mi"))
        assertNull(Reparto.minutos(null))
    }

    @Test
    fun `el tiempo crece con la distancia y nunca es cero`() {
        assertTrue(Reparto.minutos(5.0)!! > Reparto.minutos(1.0)!!)
        // 3 mi por 1,3 de callejero a 18 mph = 13 min
        assertEquals(13, Reparto.minutos(3.0))
        // Cruzar la calle sigue costando un minuto, no cero.
        assertEquals(1, Reparto.minutos(0.01))
    }

    @Test
    fun `el dinero sale con dos decimales`() {
        assertEquals("$23.40", Reparto.dinero(23.4, "USD"))
        assertEquals("$0.05", Reparto.dinero(0.05, "USD"))
        assertEquals("$100.00", Reparto.dinero(100.0, "USD"))
        assertEquals("C$450.00", Reparto.dinero(450.0, "NIO"))
    }

    // ── lo que dice el cartel ───────────────────────────────────────────────

    @Test
    fun `el cobro distingue efectivo, tarjeta y ya pagado`() {
        assertTrue(Reparto.lineaCobro(oferta()).contains("efectivo"))
        assertTrue(Reparto.lineaCobro(oferta(metodo = "card_on_pickup")).contains("tarjeta"))
        val pagado = Reparto.lineaCobro(oferta(cobrar = null, metodo = "online"))
        assertTrue(pagado.contains("no cobres"))
        // 🚨 Cobrar dos veces es peor que no cobrar: en un pedido ya pagado no
        // puede aparecer ningun importe.
        assertFalse(pagado.contains("23"))
    }

    @Test
    fun `la distancia del cartel sale de donde esta el movil`() {
        val con = Reparto.lineaDistancia(oferta(), 34.0522, -118.2437, "mi")
        assertNotNull(con)
        assertTrue(con!!.contains("de ti"))
        assertTrue(con.contains("min"))
    }

    @Test
    fun `sin posicion propia se usa la del local y se dice`() {
        val sin = Reparto.lineaDistancia(oferta(), null, null, "mi")
        assertNotNull(sin)
        // No puede decir "de ti" cuando no sabe donde esta uno: seria mentir
        // sobre la unica cifra que decide si se acepta el pedido.
        assertTrue(sin!!.contains("del local"))
        assertTrue(sin.contains("2.3 mi"))
    }

    @Test
    fun `sin ningun dato de distancia no se escribe nada`() {
        val nada = Reparto.lineaDistancia(
            oferta(lat = null, lng = null, millasLocal = null), null, null, "mi",
        )
        assertNull(nada)
    }

    @Test
    fun `el detalle se recorta y la nota va aparte`() {
        val larga = oferta(
            nota = "Timbre roto",
            articulos = listOf("1 A", "1 B", "1 C", "1 D", "1 E", "1 F"),
        )
        val d = Reparto.detalle(larga, max = 4)
        assertEquals(6, d.size)                       // 4 + "y 2 mas" + nota
        assertTrue(d.any { it.contains("y 2 más") })
        assertTrue(d.last().trim() == "Timbre roto")
    }

    // ── lectura de la respuesta ─────────────────────────────────────────────

    private val json = """
        {"disponible":true,"unidad":"km","local":{"lat":33.9,"lng":-118.2},
         "entregas":[
           {"order_id":"a1","order_key":"W-1042","status":"ready",
            "customer_name":"Ana","customer_phone":"3105550000",
            "address":"1234 W 54th St","address_lat":34.07,"address_lng":-118.26,
            "note":"Timbre roto","total":23.4,"currency":"USD",
            "payment_method":"cash_on_pickup","created_at":"2026-09-07 04:00:00",
            "repartidor_id":null,"mio":false,"cobrar":23.4,"millas_local":2.31,
            "articulos":[{"n":2,"nombre":"Tacos"},{"n":1,"nombre":"Horchata"}]},
           {"order_id":"b2","order_key":"W-1043","status":"en_camino",
            "customer_name":"Luis","customer_phone":null,
            "address":"999 Main St","address_lat":null,"address_lng":null,
            "note":null,"total":10.0,"currency":"USD",
            "payment_method":"online","created_at":"2026-09-07 04:05:00",
            "repartidor_id":"r1","mio":true,"cobrar":null,"millas_local":null,
            "articulos":[]}
         ]}
    """.trimIndent()

    @Test
    fun `lee la respuesta entera`() {
        val d = Reparto.leer(json)
        assertNotNull(d)
        assertTrue(d!!.disponible)
        assertEquals("km", d.unidad)
        assertEquals(2, d.ofertas.size)
        assertEquals(1, d.libres.size)
        assertEquals(1, d.mios.size)

        val a = d.ofertas[0]
        assertEquals("W-1042", a.clave)
        assertEquals(34.07, a.lat!!, 1e-9)
        assertEquals(23.4, a.cobrar!!, 1e-9)
        assertFalse(a.pagadoOnline)
        assertEquals(listOf("2  Tacos", "1  Horchata"), a.articulos)
    }

    @Test
    fun `un null del JSON no se convierte en cero`() {
        val d = Reparto.leer(json)!!
        val b = d.ofertas[1]
        // 🚨 optDouble devuelve NaN donde el JSON trae null, y NaN pasado a
        // haversine da una distancia que no existe. Aqui tiene que ser null.
        assertNull(b.lat)
        assertNull(b.lng)
        assertNull(b.millasLocal)
        // Y un pedido ya pagado no puede leerse como "cobrar 0".
        assertNull(b.cobrar)
        assertTrue(b.pagadoOnline)
        assertNull(b.telefono)
    }

    @Test
    fun `una respuesta rota NO es una lista vacia`() {
        // Devolver una lista vacia aqui haria desaparecer de la pantalla
        // pedidos que siguen existiendo.
        assertNull(Reparto.leer(null))
        assertNull(Reparto.leer(""))
        assertNull(Reparto.leer("no soy json"))
        assertNull(Reparto.leer("""{"error":"caducado"}"""))
        // Pero una lista de verdad vacia SI es un dato.
        val vacia = Reparto.leer("""{"disponible":true,"unidad":"mi","entregas":[]}""")
        assertNotNull(vacia)
        assertEquals(0, vacia!!.ofertas.size)
    }

    @Test
    fun `sin disponible el campo se lee como falso`() {
        val d = Reparto.leer("""{"unidad":"mi","entregas":[]}""")
        assertNotNull(d)
        assertFalse(d!!.disponible)
    }

    // ── a quien se le anuncia ───────────────────────────────────────────────

    @Test
    fun `cada pedido se anuncia una sola vez y nunca en la primera lectura`() {
        Vigia.reiniciar()
        val uno = Datos(true, "mi", listOf(oferta(id = "a1")))
        // La primerisima lectura calla: al abrir la app saldrian de golpe
        // carteles de pedidos que ya llevaban ahi media hora.
        assertEquals(0, Vigia.nuevas(uno).size)

        val dos = Datos(true, "mi", listOf(oferta(id = "a1"), oferta(id = "a2")))
        assertEquals(listOf("a2"), Vigia.nuevas(dos).map { it.orderId })
        // Releer lo mismo no vuelve a anunciar nada.
        assertEquals(0, Vigia.nuevas(dos).size)
    }

    @Test
    fun `lo que ya lleva uno no se le ofrece`() {
        Vigia.reiniciar()
        Vigia.nuevas(Datos(true, "mi", emptyList()))
        val d = Datos(true, "mi", listOf(oferta(id = "z9", mio = true)))
        assertEquals(0, Vigia.nuevas(d).size)
    }

    @Test
    fun `un parpadeo de la lista no vuelve a anunciar el mismo pedido`() {
        Vigia.reiniciar()
        Vigia.nuevas(Datos(true, "mi", emptyList()))
        val con = Datos(true, "mi", listOf(oferta(id = "a1")))
        assertEquals(1, Vigia.nuevas(con).size)

        // Dos lecturas en las que no aparece (red inestable) y vuelve: no
        // puede volver a sonar. Solo a la TERCERA ausencia se da por ido.
        val sin = Datos(true, "mi", emptyList())
        Vigia.nuevas(sin); Vigia.nuevas(sin)
        assertEquals(0, Vigia.nuevas(con).size)
    }

    @Test
    fun `tras irse de verdad, si vuelve se anuncia otra vez`() {
        Vigia.reiniciar()
        Vigia.nuevas(Datos(true, "mi", emptyList()))
        val con = Datos(true, "mi", listOf(oferta(id = "a1")))
        Vigia.nuevas(con)
        val sin = Datos(true, "mi", emptyList())
        repeat(3) { Vigia.nuevas(sin) }
        assertEquals(1, Vigia.nuevas(con).size)
    }
}
