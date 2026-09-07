package com.yammbo.reparto

import org.json.JSONObject
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * El nucleo sin Android: modelo, distancias y textos.
 *
 * Vive aparte a proposito. Es lo unico de la app que se puede probar en la JVM
 * sin un movil delante, y es justo donde se esconden los errores caros: una
 * conversion mal hecha manda a alguien a 5 km de donde tiene que ir y nadie se
 * entera hasta que el cliente llama.
 */

/** Un pedido tal y como lo devuelve /repartidor/<token>/data. */
data class Oferta(
    val orderId: String,
    val clave: String,
    val direccion: String,
    val lat: Double?,
    val lng: Double?,
    val cliente: String,
    val telefono: String?,
    val nota: String?,
    /** Importe a cobrar en la puerta, o null si ya se pago en linea. */
    val cobrar: Double?,
    val moneda: String,
    val metodoPago: String,
    /** Millas del local a la casa, calculadas al comprar. */
    val millasLocal: Double?,
    val mio: Boolean,
    val articulos: List<String>,
) {
    val pagadoOnline: Boolean get() = cobrar == null
}

/** Lo que trae una respuesta de /data. */
data class Datos(
    val disponible: Boolean,
    val unidad: String,
    val ofertas: List<Oferta>,
) {
    val libres: List<Oferta> get() = ofertas.filter { !it.mio }
    val mios: List<Oferta> get() = ofertas.filter { it.mio }
}

object Reparto {

    /** Millas entre dos puntos por la formula del haversine. */
    fun millas(aLat: Double, aLng: Double, bLat: Double, bLng: Double): Double {
        val r = 3958.7613                      // radio terrestre medio, en millas
        fun rad(g: Double) = g * PI / 180.0
        val dLat = rad(bLat - aLat)
        val dLng = rad(bLng - aLng)
        val h = sin(dLat / 2) * sin(dLat / 2) +
            cos(rad(aLat)) * cos(rad(bLat)) * sin(dLng / 2) * sin(dLng / 2)
        return 2 * r * asin(min(1.0, sqrt(h)))
    }

    /**
     * Distancia escrita como la diria alguien.
     *
     * Por debajo de un par de manzanas los decimales de milla no dicen nada, y
     * a partir de diez tampoco: "12,4 mi" y "12 mi" se conducen igual.
     */
    fun distancia(mi: Double?, unidad: String): String? {
        if (mi == null || !mi.isFinite() || mi < 0) return null
        if (unidad == "km") {
            val km = mi * 1.609344
            if (km < 0.3) return "" + (km * 1000).roundToInt() + " m"
            return (if (km < 10) unDecimal(km) else "" + km.roundToInt()) + " km"
        }
        if (mi < 0.2) return "" + ((mi * 5280 / 10).roundToInt() * 10) + " ft"
        return (if (mi < 10) unDecimal(mi) else "" + mi.roundToInt()) + " mi"
    }

    /** Un decimal con punto, sin depender de la configuracion regional. */
    private fun unDecimal(v: Double): String {
        val d = (v * 10).roundToInt()
        return "" + (d / 10) + "." + (d % 10)
    }

    /**
     * Cuanto se tarda, aproximado y dicho como aproximado.
     *
     * Linea recta por 1,3 para aproximar el callejero, a 18 mph de media
     * urbana. No promete una hora de llegada: no hay trafico en esta cuenta.
     */
    fun minutos(mi: Double?): Int? {
        if (mi == null || !mi.isFinite() || mi < 0) return null
        return maxOf(1, (mi * 1.3 / 18 * 60).roundToInt())
    }

    /** Importe con dos decimales y su simbolo, sin Intl ni locales. */
    fun dinero(v: Double?, moneda: String): String {
        if (v == null) return ""
        val simbolo = when (moneda.uppercase()) {
            "USD" -> "$"
            "EUR" -> "€"
            "NIO" -> "C$"
            else -> ""
        }
        val centavos = (abs(v) * 100).roundToInt()
        val txt = "" + (centavos / 100) + "." + (centavos % 100).toString().padStart(2, '0')
        return (if (v < 0) "-" else "") + simbolo + txt
    }

    /**
     * La linea de dinero del cartel.
     *
     * Es lo unico del aviso que cuesta dinero si se pasa por alto, asi que se
     * dice entero: cuanto y en que. "Ya pagado" tambien importa — cobrar dos
     * veces es peor que no cobrar.
     */
    fun lineaCobro(o: Oferta, t: Textos): String = when {
        o.pagadoOnline -> t.cobroPagado
        o.metodoPago == "card_on_pickup" -> t.cobroTarjeta.format(dinero(o.cobrar, o.moneda))
        else -> t.cobroEfectivo.format(dinero(o.cobrar, o.moneda))
    }

    /**
     * El renglon de debajo del titulo del cartel: a cuanto esta y cuanto se
     * tarda. Si no se sabe donde esta el movil, no se inventa una distancia.
     */
    fun lineaDistancia(
        o: Oferta, desdeLat: Double?, desdeLng: Double?, unidad: String, t: Textos,
    ): String? {
        val propia = desdeLat != null && desdeLng != null && o.lat != null && o.lng != null
        val mi = when {
            propia -> millas(desdeLat!!, desdeLng!!, o.lat!!, o.lng!!)
            // Sin posicion propia sirve la del local: sigue diciendo si el
            // pedido es de la esquina o de la otra punta.
            else -> o.millasLocal
        } ?: return null
        val d = distancia(mi, unidad) ?: return null
        val m = minutos(mi)
        // No puede decir "de ti" cuando no se sabe donde esta uno: seria mentir
        // sobre la unica cifra que decide si se acepta el pedido.
        val desde = if (propia) t.deTi else t.delLocal
        return d + " " + desde + (if (m != null) " · " + t.aprox.format(m) else "")
    }

    /** El detalle del pedido para el cartel, recortado a lo que cabe. */
    fun detalle(o: Oferta, t: Textos, max: Int = 4): List<String> {
        val l = ArrayList<String>()
        o.articulos.take(max).forEach { l.add(it) }
        if (o.articulos.size > max) l.add(t.yMas.format(o.articulos.size - max))
        if (!o.nota.isNullOrBlank()) l.add(" " + o.nota)
        return l
    }

    // ── lectura de la respuesta ─────────────────────────────────────────────

    /**
     * Un JSON que no se entiende NO es una lista vacia.
     *
     * Devolver Datos(disponible=false, ofertas=[]) ante un fallo de red haria
     * desaparecer de la pantalla un pedido que sigue existiendo. Por eso esto
     * devuelve null y quien llama decide no tocar nada.
     */
    fun leer(cuerpo: String?): Datos? {
        if (cuerpo.isNullOrBlank()) return null
        return runCatching {
            val j = JSONObject(cuerpo)
            val arr = j.optJSONArray("entregas") ?: return null
            val lista = ArrayList<Oferta>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("order_id")
                if (id.isBlank()) continue
                val arts = ArrayList<String>()
                val ja = o.optJSONArray("articulos")
                for (k in 0 until (ja?.length() ?: 0)) {
                    val a = ja!!.optJSONObject(k) ?: continue
                    arts.add(a.optInt("n", 1).toString() + "  " + a.optString("nombre"))
                }
                lista.add(
                    Oferta(
                        orderId = id,
                        clave = o.optString("order_key"),
                        // Se guarda tal cual, vacia si viene vacia: el texto de
                        // relleno depende del idioma y aqui no hay recursos.
                        direccion = o.optString("address"),
                        lat = numero(o, "address_lat"),
                        lng = numero(o, "address_lng"),
                        cliente = o.optString("customer_name"),
                        telefono = o.optString("customer_phone").ifBlank { null },
                        nota = o.optString("note").ifBlank { null },
                        cobrar = numero(o, "cobrar"),
                        moneda = o.optString("currency").ifBlank { "USD" },
                        metodoPago = o.optString("payment_method"),
                        millasLocal = numero(o, "millas_local"),
                        mio = o.optBoolean("mio", false),
                        articulos = arts,
                    )
                )
            }
            Datos(
                disponible = j.optBoolean("disponible", false),
                unidad = if (j.optString("unidad") == "km") "km" else "mi",
                ofertas = lista,
            )
        }.getOrNull()
    }

    /** optDouble devuelve NaN donde el JSON trae null; aqui eso es "no se sabe". */
    private fun numero(j: JSONObject, clave: String): Double? {
        if (j.isNull(clave)) return null
        val v = j.optDouble(clave, Double.NaN)
        return if (v.isNaN()) null else v
    }
}
