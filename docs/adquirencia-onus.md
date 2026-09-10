# Adquirencia y emisor: ruta on-us y compensación

Cuando una tarjeta emitida por nuestro CMS se usa en una terminal de nuestra propia
adquirencia (`puntos-adquisicion-pos`), la operación no tiene por qué salir a PROSA ni a
E-Global: el adquirente y el emisor son la misma casa. Esta integración cierra ese circuito:
el adquirente autoriza directo contra el CMS por ISO 8583, anula con un reverso, y al final
del día le entrega al CMS un archivo de compensación con sus ventas on-us para que capture y
liquide. Sin intercambio.

```
terminal ──venta──▶ backend de adquirencia ──0200 ISO 8583 (TCP 8583)──▶ CMS autorizador ──▶ retención HELD
                    (BIN on-us: 453211, 453212, ...)                       39=00, 38=folio, 37=RRN
terminal ──anular─▶ backend de adquirencia ──0400 (mismo RRN)────────────▶ CMS ──▶ RELEASED
cierre del día ───▶ POST /clearing/cms/submit ──CMS-CLR (RRN + folio)──▶ POST /api/clearing/files ──▶ CAPTURED
                                                                           ciclo DOMESTIC: presentaciones sin intercambio
```

## Lado del adquirente (`BackendAdquiriencia`)

- **Nueva ruta `CMS_ISSUER`** en `SwitchType` y el adaptador
  `CmsIssuerSwitchClientAdapter`: habla ISO 8583 (1987, ASCII, mapas de bits en hexadecimal,
  longitud de 2 bytes en el cable) con el canal del CMS, con el códec `CmsIso8583Codec`.
  Arma el 0100/0200/0400 con PAN (2), código de proceso (3), monto en centavos (4), fecha y
  hora (7, 12, 13), STAN (11), MCC (18), modo de entrada por canal (22: 051 chip, 071 sin
  contacto, 812 comercio electrónico), condición (25), adquirente (32), RRN (37), terminal
  (41), afiliación (42), nombre y país (43) y moneda (49). Lee 39, 38 y 37 de la respuesta.
  La salud se mide con un eco 0800/0810.
- **Enrutamiento**: `SmartRoutingServiceImpl` consulta primero `esOnUs(bin)` del adaptador.
  Si el BIN es nuestro y el CMS responde, la ruta es `ON_US_ISSUER` con intercambio cero; si
  el CMS no responde, sigue la ruta de la marca.
- **BIN sincronizados desde el CMS**: `CmsBinDirectory` pide al CMS su catálogo
  (`GET /api/products/bins`, con `cms.api.key`) al arrancar y cada
  `switch.cms.bin-sync.interval-ms` (5 minutos). Hasta la primera respuesta vale la semilla
  `switch.cms.bins`; después manda el CMS: un producto nuevo se reconoce en el siguiente ciclo
  sin reiniciar ni tocar configuración, y uno desactivado deja de ir on-us. Si el CMS se cae se
  conserva la última lista buena. `GET /api/v1/switches/cms/bins` muestra la lista, la fuente y
  la última sincronización; `POST /api/v1/switches/cms/bins/refresh` (administrador) la fuerza. Aplica a venta,
  preautorización y anulación del mismo día. La devolución de una venta on-us va al CMS
  como 0200 con código de proceso 20xxxx y el RRN de la venta original: el CMS ubica la
  tarjeta por ese RRN, comprueba que no supere el monto original y abona por el mismo puerto
  de fondos (ledger, core o línea). La captura diferida de una preautorización on-us viaja
  como 0220 con el RRN y el monto final: el CMS captura la retención (parcial si el monto
  es menor) y responde 00; cuando esa captura se presenta después en el archivo de
  compensación, el CMS la reconoce como capturada antes y la casa sin excepción (solo una
  segunda presentación se marca como duplicado).
- **PAN**: el backend solo persiste el PAN enmascarado. El PAN completo del cargo se mantiene
  en memoria (`Transaccion.panClaro`, transitorio) el tiempo de la autorización, que es lo
  que el emisor necesita para ubicar la tarjeta. El reverso lleva el RRN de la venta
  original, que es como el CMS lo localiza.
- **Compensación**: tipo de archivo `CMS_CLR` y generador `CmsClrClearingGenerator`
  (formato CMS-CLR 1.0, red `DOMESTIC`, una presentación por venta on-us aprobada con RRN,
  folio y PAN enmascarado). `POST /api/v1/clearing/cms/submit?fecha=` arma el lote del día
  solo con ventas `CMS_ISSUER` (los archivos Visa, Mastercard y PROSA las excluyen) y lo
  entrega al CMS con `CmsClearingSubmitter`. Requiere `cms.api.key`.

Configuración (`application.yml`):

| Variable | Por defecto | Uso |
|---|---|---|
| `SWITCH_CMS_ENABLED` | `true` | apaga la ruta on-us |
| `SWITCH_CMS_HOST` / `SWITCH_CMS_PORT` | `127.0.0.1` / `8583` | canal ISO del CMS |
| `SWITCH_CMS_TIMEOUT` | `3000` | ms por mensaje |
| `SWITCH_CMS_BINS` | `453211,453212,453213,601100,541234` | semilla de BIN, válida hasta que el CMS responda |
| `SWITCH_CMS_BIN_SYNC_ENABLED` / `SWITCH_CMS_BIN_SYNC_INTERVAL_MS` | `true` / `300000` | sincronización del catálogo de BIN con el CMS |
| `SWITCH_CMS_ACQUIRER_ID` | `4800684` | campo 32 |
| `CMS_API_URL` / `CMS_API_KEY` | `http://127.0.0.1:8085` / vacío | entrega del archivo de compensación |

## Lado del emisor (CMS)

- **Catálogo de BIN para adquirentes**: `GET /api/products/bins?activeOnly=true` (permiso de
  lectura o clave de API de sistemas) devuelve BIN, red, tipo, código y nombre de cada producto
  activo. Es lo que el adquirente propio sincroniza; sirve igual para cualquier switch que deba
  reconocer nuestras tarjetas.
- El canal ISO 8583 y el autorizador no cambian: el mensaje del adquirente entra como
  cualquier otro (controles, fraude, límites, fondos), con RRN, STAN y adquirente guardados en
  la retención.
- **Compensación con PAN enmascarado**: `ClearingService.findCard` resuelve la tarjeta por
  PAN cuando viene completo y, si no, por el RRN o el folio de autorización de la línea, con
  los últimos cuatro del PAN como comprobación. Así el archivo del adquirente casa sin que
  éste guarde el PAN en claro.
- El ciclo de liquidación de las ventas on-us es el de la red `DOMESTIC`, con intercambio
  cero: la posición neta refleja solo lo que se movió entre tarjetahabientes y comercios de la
  misma casa.

## Prueba integral con el POS virtual

El portal del adquirente tiene un **POS virtual** (Motor de Adquirencia → POS virtual,
`/dashboard/adquirencia/pos-virtual`): una terminal emulada que manda al backend lo mismo que
una terminal física (PAN completo, monto, canal, terminal y comercio) y muestra lo que la
terminal imprimiría: código de respuesta, folio, RRN y la ruta (on-us al CMS o la red). Desde
el ticket de cada operación se captura una preautorización, se anula o se devuelve. Recorrido
completo terminal → adquirencia → CMS autorizador → core:

1. En la consola del CMS, pestaña 3, emitir una tarjeta (o usar una existente) y elegir en su
   fila la acción **"Datos para POS de prueba"**: muestra PAN, vencimiento, CVV2 y PIN. Solo
   existe con `HSM_EXPOSE_TEST_SECRETS=true` (bancos de prueba y demostración).
2. En el POS virtual, pegar el PAN: el portal avisa que el BIN es del CMS y que se autorizará
   on-us. Elegir terminal (el comercio se toma de la terminal), monto y modo de entrada.
3. **Venta**: el adquirente enruta al CMS por ISO 8583; el CMS valida, retiene contra el saldo
   (ledger, core o línea) y responde 00 con folio y RRN. En el CMS la retención aparece HELD
   con ese RRN (pestaña 6 o vista 360 de la tarjeta).
4. **Anular** desde el ticket → 0400 al CMS → retención RELEASED. **Preautorización** →
   **Capturar** (parcial permitida) → 0220 → CAPTURED. **Devolver** una venta → abono al titular.
5. Cierre del día: `POST /api/v1/clearing/cms/submit` (o la pestaña Compensación & Clearing)
   entrega el CMS-CLR y el CMS captura y liquida sin intercambio.

## Portal publicado

El frontend del adquirente (`FrontendAdquiriencia`, Next.js) está publicado en
**https://ac.alocashfintech.com/pos/login** sobre el mismo servidor del CMS. Comparte dominio
con la consola, así que se construye con `NEXT_BASE_PATH=/pos` (nuevo `basePath` en
`next.config.mjs`), `NEXT_PUBLIC_API_URL=/pos/api/v1` y `API_BASE_URL=http://127.0.0.1:4000`:
el navegador solo habla con `ac.alocashfintech.com` y es el propio Next quien reenvía
`/pos/api/v1/*` al backend de adquirencia, por lo que no hace falta CORS ni abrir 4000. Corre
como `pos-frontend.service` (usuario `cms`, `/opt/cms/pos-frontend`, `node server.js` en
127.0.0.1:3100) y nginx lo sirve en `location ^~ /pos` del sitio `cms-mexico`. Acceso de
demostración: `mesa@finsus.mx` (contraseña en el archivo de credenciales del servidor).

Para republicarlo: en `FrontendAdquiriencia` exportar esas tres variables, `npx next build`,
empaquetar `.next/standalone` + `.next/static` (en `.next/static` dentro del standalone) +
`public`, subir a `/opt/cms/pos-frontend` y `systemctl restart pos-frontend`. Si algún día se le
da su propio subdominio (registro A a 54.86.249.232), basta construir sin `NEXT_BASE_PATH`,
con `NEXT_PUBLIC_API_URL=/api/v1`, y darle su sitio de nginx con certbot.

## Verificación

`python scripts/verify_acquiring_onus.py` con el CMS en 8085 y el backend de adquirencia con
la ruta on-us en `POS_URL` (por defecto `http://localhost:4100/api/v1`; en local se levanta
con `SERVER_PORT=4100 MANAGEMENT_PORT=9405 CMS_API_KEY=dev-api-key DB_URL=jdbc:postgresql://localhost:5432/adquiriencia_pos DB_USERNAME=postgres DB_PASSWORD=... mvn spring-boot:run`).
28 comprobaciones: BIN on-us tomados del catálogo del CMS (producto nuevo reconocido tras sincronizar y enrutado on-us, desactivado sale de la lista), venta aprobada on-us con folio y RRN reflejados en la retención del CMS,
51 por fondos, 61 por límite diario, tarjeta ajena por la ruta de la marca, anulación que
libera, preautorización y captura parcial por 0220, archivo CMS-CLR aceptado y casado por
RRN con captura (la captura previa se reconoce sin excepción), ciclo sin intercambio,
devolución parcial abonada al titular y rechazada cuando supera la venta, y segunda
presentación tratada como duplicado sin cobrar dos veces. Borra sus datos en las dos bases.
