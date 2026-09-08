# Compensación y liquidación con los archivos de la red

El autorizador reserva, nunca debita. Lo que realmente cobra el comercio llega después,
en el archivo de compensación de la red (Visa, Mastercard o la red doméstica). Este módulo
carga ese archivo, casa cada línea contra las retenciones del autorizador, captura lo que
corresponde, abre una excepción por lo que no cuadra y arma la posición neta que tesorería
paga o cobra a la red por cada ciclo.

## Flujo

```
red ──archivo CMS-CLR──▶ POST /api/clearing/files ──▶ lote (ClearingBatch)
                                                     │  una transacción por línea
                                                     ├─ PRESENTMENT  → captura la retención (o forzado sin autorización)
                                                     ├─ REVERSAL     → abona al titular
                                                     ├─ CHARGEBACK   → se liga a la aclaración
                                                     ├─ REPRESENTMENT→ se liga a la aclaración
                                                     └─ FEE          → cuota de red
                                                     ▼
                                              ciclo de liquidación (red + fecha)
                                              neto = presentaciones − reversos − contracargos − intercambio
                                              OPEN → CLOSED (archivo sellado) → PAID (referencia SPEI)
```

## Formato del archivo (CMS-CLR 1.0)

Texto plano, un registro por línea, campos separados por `|`, sin comillas. Es el formato
neutro que el CMS entiende; el adaptador de cada red (Visa BASE II, Mastercard IPM, doméstica)
traduce su formato propietario a este antes de cargarlo. Ver
[contratos-terceros.md](contratos-terceros.md) para lo que hay que contratar con cada red.

```
HDR|CMS-CLR|1.0|VISA|2026-09-08|VISA-20260908-1
REC|PRESENTMENT|4532129876543210|000000000123|000123|ABC123|30.00|MXN|0.35|5411|MERCH1|Tienda Centro|2026-09-08|
REC|REVERSAL|4532129876543210|000000000123|000123|ABC123|30.00|MXN|0|5411|MERCH1|Tienda Centro|2026-09-08|
REC|CHARGEBACK|4532129876543210|000000000456||DEF456|80.00|MXN|0|5411||Contracargo|2026-09-08|13.1
REC|FEE|||||38.70|MXN|0|||Cuota de red|2026-09-08|NETFEE
TRL|4|140.00
```

| Campo | Contenido |
|---|---|
| `HDR` | formato, versión, red (`VISA`, `MASTERCARD`, `DOMESTIC`), fecha del ciclo, id del archivo |
| `REC` | tipo, PAN, RRN (DE37), STAN (DE11), id de aprobación (DE38, últimos 6 del código), monto, moneda, cuota de intercambio, MCC, id del comercio, nombre del comercio, fecha de la transacción, código de razón |
| `TRL` | número de registros y suma de montos **sin contar las cuotas** (`FEE`) |

El archivo se rechaza completo (`422 CLEARING_FILE_INVALID`) si falta la cola, si la cuenta o
el total no coinciden, si la red o el tipo son desconocidos. El mismo archivo cargado dos
veces (mismo SHA-256) devuelve `409 CLEARING_FILE_DUPLICATE`.

## Casado y resultados por línea

Cada línea se procesa en su propia transacción: una línea que falla no tumba el lote.

| Resultado | Qué pasó | Efecto en fondos | Excepción en conciliación |
|---|---|---|---|
| `MATCHED_CAPTURED` | presentación casa con una retención viva por el mismo monto (o hasta la tolerancia, 15 %) | captura; si vino por más dentro de tolerancia, debita la diferencia | no |
| `AMOUNT_MISMATCH` | presentación por más de la tolerancia | captura lo retenido; la diferencia queda en `CLEARING_AMOUNT_MISMATCH` | sí |
| `ALREADY_CAPTURED` | segunda presentación de la misma autorización | ninguno | `CLEARING_DUPLICATE` |
| `FORCE_POSTED` | presentación sin retención viva (autorización liberada, expirada o inexistente) | crea una retención `CLR-…` ya capturada: el emisor debe a la red aunque no haya autorizado | `CLEARING_NO_AUTHORIZATION` |
| `NO_CARD` | el PAN no es nuestro | ninguno | `CLEARING_NO_CARD` |
| `REVERSED` | reverso de compensación de una presentación ya capturada | abono al titular (o libera si aún estaba retenida) | no |
| `DISPUTE_LINKED` | contracargo o representación ligada a una aclaración abierta | ninguno (la aclaración ya lo hizo) | no |
| `FEE_BOOKED` | cuota de red | ninguno en cuentas de clientes; suma al intercambio del ciclo | no |
| `UNMATCHED` | reverso o contracargo sin autorización ni aclaración | ninguno | `CLEARING_UNMATCHED` |
| `ERROR` | fallo técnico en la línea | ninguno | ninguno; ver detalle |

Las presentaciones se casan primero por RRN y después por el id de aprobación (los seis
caracteres que van en DE38), siempre sobre la misma tarjeta. Los ítems de conciliación se
identifican como `CLEARING:<lote>:<línea>` y se atienden en la pantalla de conciliación.

## Liquidación por ciclo

Un ciclo es una red y una fecha. Cada lote cargado suma al ciclo abierto:

```
neto = presentaciones − reversos − contracargos − intercambio
neto > 0 : el emisor paga a la red
neto < 0 : el emisor recibe de la red
```

- `POST /api/clearing/settlement/cycles/{id}/close` congela las cifras, genera el archivo de
  liquidación (CSV: un renglón por lote, total y neto) y lo sella con SHA-256. Auditado.
- Un archivo tardío para un ciclo cerrado devuelve `409 SETTLEMENT_CYCLE_CLOSED`: va a un
  ciclo nuevo (otra fecha), nunca reabre el cerrado.
- `POST .../paid {paymentRef}` registra la transferencia (SPEI). Requiere referencia. Auditado.
- `GET .../file` descarga el CSV con `X-File-SHA256`.

## Simulador de la red

`POST /api/clearing/simulate` juega a ser la red: construye el archivo de un ciclo con todo
lo que el autorizador tiene retenido en los últimos 7 días (tarjetas con PAN en la bóveda) y,
si se pide, las anomalías que trae un ciclo real:

| `anomalies` | Qué mete |
|---|---|
| `over` | la primera presentación llega por 1.5× (propina fuera de tolerancia) |
| `duplicate` | repite la última presentación |
| `unknown` | una presentación con un PAN que no es nuestro |
| `reversal` | reverso de la última presentación |
| `chargeback` | contracargo de una aclaración en `CHARGEBACK_SENT` |
| `fee` | una cuota de red de 38.70 |

Con `ingest: true` lo carga en el mismo paso. `cardId` limita el archivo a una tarjeta.

## API

| Método | Ruta | Permiso |
|---|---|---|
| `POST` | `/api/clearing/files` | `CMS:OPERATE` |
| `POST` | `/api/clearing/simulate` | `CMS:OPERATE` |
| `GET` | `/api/clearing/batches`, `/batches/{id}`, `/batches/{id}/records?outcome=`, `/batches/{id}/file`, `/exceptions` | `CMS:READ` |
| `GET` | `/api/clearing/settlement/cycles`, `/{id}`, `/{id}/file` | `CMS:READ` |
| `POST` | `/api/clearing/settlement/cycles/{id}/close`, `/{id}/paid` | `CMS:OPERATE` |

## Configuración

| Propiedad | Variable | Por defecto |
|---|---|---|
| `clearing.tolerance-percent` | `CLEARING_TOLERANCE_PERCENT` | `15` |
| `clearing.simulated-interchange-percent` | `CLEARING_SIM_INTERCHANGE_PERCENT` | `1.15` |
| `clearing.currency` | `CLEARING_CURRENCY` | `MXN` |

## Consola

Pestaña **18. Compensación & Liquidación**: archivos (cargar uno, simular un ciclo, ver cada
línea con su resultado, descargar), liquidación por ciclo (cerrar, registrar pago, descargar
el archivo sellado) y excepciones.

## Verificación

`python scripts/verify_clearing.py` contra un CMS en `localhost:8085` con `CORE_MODE=fineract`
y la clave de API de scripts: 30 comprobaciones que cubren los siete resultados, el efecto en
el ledger, los totales del lote, el rechazo de duplicados y colas inconsistentes, el ciclo
abierto → cerrado → pagado, el archivo sellado y la auditoría. Borra sus datos al terminar.
