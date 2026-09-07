package com.yammbo.reparto

import android.content.Context

/**
 * Los textos que necesita el nucleo, ya resueltos.
 *
 * `Reparto` no recibe un Context a proposito: es lo unico de la app que se
 * puede probar en la JVM sin un movil delante, y meterle recursos de Android
 * dentro obligaria a montar Robolectric para comprobar una division. Asi las
 * pruebas construyen un Textos a mano y la app lo saca de strings.xml.
 */
data class Textos(
    val cobroEfectivo: String,
    val cobroTarjeta: String,
    val cobroPagado: String,
    val deTi: String,
    val delLocal: String,
    val aprox: String,
    val yMas: String,
    val sinDireccion: String,
) {
    companion object {
        fun de(ctx: Context): Textos = Textos(
            cobroEfectivo = ctx.getString(R.string.cobro_efectivo),
            cobroTarjeta = ctx.getString(R.string.cobro_tarjeta),
            cobroPagado = ctx.getString(R.string.cobro_pagado),
            deTi = ctx.getString(R.string.dist_de_ti),
            delLocal = ctx.getString(R.string.dist_del_local),
            aprox = ctx.getString(R.string.dist_aprox),
            yMas = ctx.getString(R.string.dist_y_mas),
            sinDireccion = ctx.getString(R.string.sin_direccion),
        )
    }
}
