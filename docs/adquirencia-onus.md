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
- **Enrutamiento**: `SmartRoutingServiceImpl` consulta primero `esOnUs(bin)` del adaptador
  (`switch.cms.bins`). Si el BIN es nuestro y el CMS responde, la ruta es `ON_US_ISSUER` con
  intercambio cero; si el CMS no responde, sigue la ruta de la marca. Aplica a venta,
  preautorización y anulación del mismo día. La devolución de una venta on-us va al CMS
  como 0200 con código de proceso 20xxxx y el RRN de la venta original: el CMS ubica la
  tarjeta por ese RRN, comprueba que no supere el monto original y abona por el mismo puerto
  de fondos (ledger, core o línea). La captura diferida de una preautorización on-us no
  viaja como 0220: se liquida con el archivo de compensación.
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
| `SWITCH_CMS_BINS` | `453211,453212,453213,601100,541234` | BIN de los productos del CMS |
| `SWITCH_CMS_ACQUIRER_ID` | `4800684` | campo 32 |
| `CMS_API_URL` / `CMS_API_KEY` | `http://127.0.0.1:8085` / vacío | entrega del archivo de compensación |

## Lado del emisor (CMS)

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

## Verificación

`python scripts/verify_acquiring_onus.py` con el CMS en 8085 y el backend de adquirencia con
la ruta on-us en `POS_URL` (por defecto `http://localhost:4100/api/v1`; en local se levanta
con `SERVER_PORT=4100 MANAGEMENT_PORT=9405 CMS_API_KEY=dev-api-key DB_URL=jdbc:postgresql://localhost:5432/adquiriencia_pos DB_USERNAME=postgres DB_PASSWORD=... mvn spring-boot:run`).
19 comprobaciones: venta aprobada on-us con folio y RRN reflejados en la retención del CMS,
51 por fondos, 61 por límite diario, tarjeta ajena por la ruta de la marca, anulación que
libera, archivo CMS-CLR aceptado y casado por RRN con captura, ciclo sin intercambio,
devolución parcial abonada al titular y rechazada cuando supera la venta, y rechazo del
archivo repetido. Borra sus datos en las dos bases.
