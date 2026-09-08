# Canal ISO 8583, validación criptográfica y stand-in

## Canal ISO 8583

El CMS escucha en TCP `ISO_PORT` (8583). Trama: longitud de 2 bytes big-endian + mensaje ISO 8583
(1987) en ASCII con mapa de bits hexadecimal (primario y secundario cuando hay campos > 64).
Los campos binarios (52, 55, 128) viajan como texto hexadecimal. La tabla de campos está en
`Iso8583Codec`; adaptar el dialecto de una red concreta es cambiar esa tabla, no el manejador.

| Mensaje | Qué hace el CMS | Respuesta |
|---|---|---|
| 0800 | Gestión de red (eco) | 0810 · 39=00, eco de 70 |
| 0100 / 0200 | Busca la tarjeta por el hash del PAN (DE2 o pista 2), compara vigencia (DE14), **valida la criptografía con el HSM**, luego decide el autorizador (controles, fraude, límites, fondos) | 0110 / 0210 · 39 con el código del campo 39, 38 con el código de aprobación, 55 con ARPC (tag 91) para chip |
| 0400 / 0420 | Localiza la original por RRN (DE37) o por STAN + adquirente (DE90) y libera la retención | 0410 / 0430 · 39=00, 25 si no existe |
| 0120 / 0220 | La red aprobó por nosotros: se registra la retención como aviso de red | 0130 / 0230 · 39=00 |

Canal y tipo se derivan de DE3 (código de proceso), DE22 (modo de entrada) y DE25. Idempotencia:
DE7 + DE11 + DE41 + DE32 repetidos devuelven la misma respuesta sin nueva retención. Cada mensaje
queda en una traza enmascarada para la consola (`GET /api/iso/recent`).

## Validación criptográfica

Ninguna llave en claro sale del HSM. El CMS habla con él por comandos de host (payShield: longitud
+ cabecera + comando + campos, `PayShieldHostClient`) y guarda las llaves como *blobs* cifrados bajo
la LMK del HSM (`hsm.host.keys.*`).

| Qué | Cuándo | Comando | Si falla |
|---|---|---|---|
| PIN (bloque bajo la ZPK del adquirente, DE52) contra el PVV de la tarjeta | Cada operación con PIN | `EA` | 55 |
| CVV de pista (DE35) contra la CVK | Banda y contactless | `CY` | 05 |
| CVV2 (DE48 `CVV2=nnn`) contra la CVK, código de servicio 000 | Comercio electrónico | `CY` | N7 |
| ARQC del chip (DE55, tags 9F26/9F36/5F34 y datos CDOL) contra la llave maestra de emisor | Chip | `KQ` | 05; si es válido, ARPC en la respuesta |

Si el HSM no responde y `iso.require-hsm=true`, la operación se rechaza con 91 (fail closed).
Los rechazos criptográficos quedan en el historial de intentos: para el motor de fraude, una
tarjeta que prueba PIN y CVV parece exactamente eso.

### En la emisión

- Se genera un PAN (BIN del producto + dígitos aleatorios + Luhn) que vive **solo cifrado** (AES-256-GCM,
  `PAN_ENCRYPTION_KEY`) y como **hash** de búsqueda (HMAC, `PAN_HMAC_KEY`) en la tarjeta.
- Se elige un PIN, se cifra bajo la LMK (`BA`) y se deriva el **PVV** (`DG`), que sí se guarda. El PIN se
  entrega una sola vez (para el PIN-mailer) y no se almacena.
- CVV, CVV2 e iCVV (`CW`) se calculan cuando hacen falta (archivo de personalización) y no se guardan.
- `GET /api/cards/{id}/test-secrets` entrega PAN, PIN, CVVs y pista 2 **solo** con
  `HSM_EXPOSE_TEST_SECRETS=true`, para bancos de prueba.

### Simulador PayShield

Al simulador (repositorio PayShieldSim) se añadieron los comandos de emisor `BA`, `DG`, `EA`, `KQ` y dos
ayudantes de laboratorio: `POST /api/issuer-lab/pin-block` (el terminal: bloque de PIN bajo una ZPK) y
`POST /api/issuer-lab/arqc` (la tarjeta: ARQC con la llave de emisor). Las llaves de desarrollo generadas
con él están en `application.properties`; en cualquier otro ambiente se sustituyen por las de la ceremonia.

## Stand-in

Cuando el core no responde (`CORE_UNAVAILABLE`, o el interruptor `CoreOutageSwitch` abierto tras una
falla reciente), el autorizador decide solo para los productos cuyo saldo vive en el core:

- Límites (`standin.*`): monto máximo por operación, total y cantidad por tarjeta en 24 horas,
  canales permitidos (el retiro en cajero queda fuera). Si se supera, 91.
- La retención se aprueba **localmente** y queda marcada `stand_in` y `stand_in_pending`.
- `StandInSettleJob` intenta cada minuto colocar la reserva en el core. Cuando lo logra, queda saldada
  (`STAND_IN_SETTLED`). Si el core la rechaza (no había fondos), la retención deja de reintentarse y se
  abre un ítem de conciliación `STAND_IN_REJECTED` para que alguien lo persiga.
- Los productos prepago y crédito no necesitan stand-in: su saldo es local.

`GET /api/standin/status` muestra límites y deuda pendiente; `POST /api/standin/settle` asienta ahora.
`POST /api/core/outage {down:true}` simula la caída (solo con `CORE_ALLOW_SIMULATED_OUTAGE=true`).

## Prueba de extremo a extremo

`scripts/verify_iso8583.py` juega al conmutador (TCP 8583), al terminal y al chip (ayudantes del
simulador): eco, PAN desconocido, PIN correcto e incorrecto, CVV de pista alterado, vigencia distinta,
CVV2 correcto e incorrecto, ARQC válido (con ARPC), falso y con monto alterado, reverso por RRN, reverso
inexistente, duplicado idempotente, aviso 0120, stand-in con el core caído (aprobación, límite de monto,
canal no permitido, prepago sigue), asentamiento al volver el core y reverso del stand-in.
