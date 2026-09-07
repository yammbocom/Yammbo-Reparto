# Yammbo Reparto

App de reparto para el personal propio de un restaurante. Envuelve la pantalla
de `pos.yammbo.com/repartidor/<token>` y le añade lo que una pestaña de
navegador no puede hacer: seguir publicando la ubicación con la pantalla
apagada y **saltar encima de otras apps** con un pedido y dos botones.

Pensada para un móvil que va en un bolsillo o en un soporte de moto.

## La ubicación no es opcional

Es la condición para usar la app, no una preferencia.

Sin un punto reciente el servidor no ofrece pedidos, el reparto no se puede
asignar a quien pilla más cerca, y la página del cliente no tiene nada que
enseñar. Así que la app se planta: no hay lista, no hay cartel, y lo dice.

Se comparte durante **todo el turno**, con un latido cada 45 s. El latido no es
un detalle de implementación: quien espera parado en la puerta del local no se
mueve, y sin él su último punto envejecería hasta que el servidor lo diera por
no disponible justo cuando más lo está.

Lo que ve el **cliente** es otra cosa y mucho menos: el punto de quien lleva su
comida, solo mientras va de camino, y solo el nombre de pila. Nunca el
teléfono, nunca antes de salir, nunca después de entregar. No se guarda un
rastro: cada posición pisa a la anterior.

Al cerrar el turno (parar el servicio) el punto se retira del servidor.

## El cartel

Cuando entra un pedido y la app no está delante: notificación, tono de alarma
—se oye con el móvil en silencio, que es donde está— y un cartel dibujado sobre
lo que haya delante, con:

- la **distancia** desde donde está el móvil, y el tiempo aproximado
- la dirección y el cliente
- lo que hay que **cobrar**, o que ya está pagado
- **Aceptar** y **Rechazar**

A diferencia del cartel de cocina, este **no se cierra tocando fuera**: pide una
decisión, y descartarlo con el pulgar sin querer dejaría el pedido esperando sin
que nadie lo sepa. Se retira solo al minuto; el pedido sigue en la lista.

**Rechazar** no cancela nada: quita el pedido de *tu* pantalla. Los demás lo
siguen viendo. Si no lo quiere nadie se queda en `ready`, donde la cocina y el
panel pueden verlo y alguien puede llamar por teléfono.

## Por qué es una app y no una web

La interfaz **sigue viviendo en el servidor**: la app la carga en un WebView.
El diseño se cambia en el worker y todos los móviles lo ven al recargar, sin
repartir un APK.

| | Navegador | Esta app |
|---|---|---|
| Cartel **encima de otras apps** | Imposible | Sí (`SYSTEM_ALERT_WINDOW`) |
| Ubicación con la pantalla apagada | No | Servicio en primer plano tipo `location` |
| Sonido que atraviesa el silencio | Limitado | Tono de alarma (`USAGE_ALARM`) |
| Aceptar sin desbloquear y buscar | No | Dos botones en el cartel |

El servicio es de tipo **`location`** y no `dataSync`: ese tipo es el que
permite seguir leyendo el GPS en segundo plano sin pedir
`ACCESS_BACKGROUND_LOCATION`, siempre que se arranque con la app visible.
`dataSync` además se corta a las 6 h en Android 15 — a mitad de turno.

**No arranca sola al encender el móvil**, a propósito: ponerse a compartir la
ubicación tras un reinicio no es "estar de turno". Entrar al turno es abrir la
app.

## Reparto de trabajo con la página

Con la app delante, la página web publica la posición y lee la lista; el
servicio se aparta. Pero *estar delante* no es *estar trayendo datos*: si el
wifi se va o el token se revoca, la página se queda muda, y a los 25 s el
servicio toma el relevo.

## Actualizaciones

Mira una vez al día si hay versión nueva y ofrece instalarla.

La versión se pregunta a `pos.yammbo.com/reparto/version.json`, **no** a la API
de GitHub: sin autenticar está limitada a 60 peticiones/hora **por IP**, y en
redes móviles con NAT compartido esa cuota se agota por culpa de terceros → 403
→ la detección falla en silencio. GitHub queda de respaldo, y el APK sí se
descarga de su CDN.

> Android **no permite instalar en silencio** a una app normal. La app descarga
> el APK y abre el instalador del sistema, donde alguien confirma una vez.

🚨 **Invariante de versionado**: el último tramo del `versionName` **es** el
`versionCode` (`1.1` ↔ `1`). El respaldo deduce el código del tag de GitHub; si
se rompe esa correspondencia, la app compara mal y deja de ver actualizaciones
**en silencio**.

Todas las versiones van firmadas con la misma clave; si cambiara, Android
rechazaría la actualización.

## Instalar

Descarga el APK de la [última
release](https://github.com/yammbocom/Yammbo-Reparto/releases/latest). Google
Play Protect avisa de que no conoce al desarrollador — es lo normal fuera de
Play; toca *Instalar de todas formas*.

Al abrirla pide el **enlace de reparto**, que sale del panel en **Reparto ›
Copiar**. Ese enlace *es* la llave: no pide contraseña, solo ve los pedidos de
ese local, y se revoca desde el panel.

Permisos: **ubicación** (obligatoria), notificaciones y *mostrar sobre otras
apps* (sin él llega la notificación pero no el cartel con los botones).

## Compilar

```bash
./gradlew :app:assembleRelease   # APK firmado
./gradlew :app:testDebugUnitTest # el nucleo: distancias, unidades, lectura
```

La firma se lee de `keystore.properties`, de variables `SIGNING_*`, o del
llavero compartido de Yammbo Music. **Nunca** va en el repositorio.

Las pruebas cubren lo único comprobable sin salir a la calle: el haversine
contra distancias conocidas, cómo se escribe cada unidad, que un `null` del
JSON no acabe siendo un `0`, que una respuesta rota no se lea como una lista
vacía, y que un pedido no se anuncie dos veces.

> El `org.json` de `android.jar` es un **stub** que lanza en pruebas JVM; por
> eso el classpath de test trae una implementación real.
