# Conexión con los sistemas antifraude del gremio (ACT-094)

Requisitos de alta, canal seguro y contrato de mensajes entre el CMS y los sistemas
antifraude interbancarios que la maqueta llama **SNA** (alertas entre participantes),
**SVL** (verificación en línea de tarjetas y comercios comprometidos) y **SPC**
(aviso temprano de contracargos). Referencia funcional: Capítulo VI del CID y sección 7 del RFP.

> El contrato de mensajes de este documento es la propuesta del CMS. En cuanto el gremio
> entregue su especificación técnica, se ajusta `HttpGuildClient` y este documento; el resto
> del CMS (outbox, plazos, acciones automáticas, consola) no cambia.

## 1. Qué hace el CMS con el gremio

| Servicio | Sentido | Cuándo | Qué pasa en el CMS |
|---|---|---|---|
| SNA · alerta | CMS → gremio | El motor de fraude detecta enumeración; un analista confirma fraude o marca un comercio | La alerta entra en un **outbox** y un trabajo la envía. El gremio contesta con un **folio**. Plazo de respuesta formal para enumeración: 5 días naturales |
| SNA · alerta | gremio → CMS | Otro participante reporta una tarjeta nuestra comprometida o un comercio sospechoso | Se guarda con su folio, se **bloquea la tarjeta** o se **lista el comercio** de inmediato, y queda en revisión con plazo de **10 días hábiles**; vencido el plazo se marca como **quebranto asumido** |
| SVL · verificación | CMS → gremio | Autorización (a lo sumo una consulta por tarjeta cada 24 h) o a petición de la consola | Un positivo va a la lista de bloqueo local con origen `EXTERNAL` y el autorizador declina con `59` y motivo `GUILD_SVL_LISTED`. Si el gremio no responde, se aplica `fail-open` (autorizar) o `fail-closed` según configuración |
| SPC · prevención | CMS → gremio | Se envía un contracargo desde aclaraciones | Aviso temprano con tarjeta, monto, código de razón y autorización |

El autorizador nunca espera al gremio más que el `timeout` configurado (800 ms por defecto)
y sólo en la primera autorización de la ventana; todo lo demás es asíncrono.

## 2. Requisitos de alta ante el gremio

Lo que hay que pedir y entregar antes de cambiar `guild.mode` a `http`:

1. **Identificador de participante** (`GUILD_PARTICIPANT_ID`) asignado por el gremio al emisor.
2. **Credencial de API** (`GUILD_API_KEY`) y **secreto compartido** para la firma HMAC (`GUILD_HMAC_SECRET`), entregados por canal seguro y custodiados como cualquier otra llave (variables de entorno o gestor de secretos, nunca en el repositorio).
3. **Certificado de cliente** para mTLS emitido por la PKI del gremio, con su cadena. Se carga como un *SSL bundle* de Spring (`GUILD_SSL_BUNDLE`, ver sección 4).
4. **Direcciones IP de salida** del CMS registradas en la lista blanca del gremio.
5. **Ambientes**: URL de pruebas y de producción (`GUILD_BASE_URL`); el alta se hace primero en pruebas y se certifica con los casos de la sección 6.
6. **Contactos** de la unidad de prevención de fraude del emisor (la persona que responde alertas dentro del plazo) y ventana de atención.
7. **Calendario de días hábiles** del gremio para el cómputo del plazo de 10 días (el CMS hoy sólo descarta fines de semana).

## 3. Canal seguro

- **Transporte**: HTTPS 1.2+ con **mTLS**: el CMS presenta su certificado de cliente y valida la cadena del gremio.
- **Firma de cada petición**: cabecera `X-Signature` = HMAC-SHA256 en hexadecimal sobre `timestamp.METODO.ruta.cuerpo`, con `X-Timestamp` (epoch en segundos) para evitar repeticiones. Cabeceras `X-Participant-Id` y `X-Api-Key` identifican al participante.
- **Tiempos**: conexión y respuesta acotadas por `GUILD_TIMEOUT_MS`.
- **Datos**: nunca viaja el PAN completo; se envían BIN y últimos cuatro dígitos.
- **Trazabilidad**: cada envío, recepción, cierre y vencimiento queda en la auditoría del CMS (`GUILD_ALERT_QUEUED`, `GUILD_ALERT_SENT`, `GUILD_ALERT_RECEIVED`, `GUILD_ALERT_CLOSED`, `GUILD_ALERT_EXPIRED`, `BLOCK_CARD_GUILD_ALERT`, `BLOCKLIST_FROM_GUILD`).

## 4. Configuración

Variables de entorno (ver `.env.example`):

```
GUILD_MODE=http                      # simulated (por defecto) | http
GUILD_PARTICIPANT_ID=EMISOR-0123
GUILD_BASE_URL=https://antifraude.gremio.mx/api
GUILD_API_KEY=...
GUILD_HMAC_SECRET=...
GUILD_SSL_BUNDLE=gremio              # nombre del bundle; vacío = sin certificado de cliente
GUILD_TIMEOUT_MS=800
GUILD_FAIL_OPEN=true
GUILD_VERIFICATION_CACHE_HOURS=24
GUILD_VERIFY_ON_AUTHORIZATION=true
GUILD_CLOSE_BUSINESS_DAYS=10
GUILD_ENUMERATION_RESPONSE_DAYS=5
GUILD_AUTO_ACT_ON_INBOUND=true
```

El certificado de cliente se declara como bundle de Spring, por ejemplo en `application-prod.properties`:

```
spring.ssl.bundle.pem.gremio.keystore.certificate=file:/etc/cms/gremio/client.crt
spring.ssl.bundle.pem.gremio.keystore.private-key=file:/etc/cms/gremio/client.key
spring.ssl.bundle.pem.gremio.truststore.certificate=file:/etc/cms/gremio/ca.crt
```

## 5. Contrato de mensajes (propuesta del CMS)

Todas las rutas cuelgan de `GUILD_BASE_URL`. Cuerpos en JSON.

| Método y ruta | Cuerpo | Respuesta |
|---|---|---|
| `GET /v1/health` | — | `{ "status": "ok" }` |
| `POST /v1/verify/card` | `{ "bin", "last4" }` | `{ "listed": bool, "folio", "reason", "listedAt" }` |
| `POST /v1/verify/merchant` | `{ "merchantId" }` | igual que arriba |
| `POST /v1/alerts` | `{ "localRef", "type", "bin", "last4", "merchantId", "merchantName", "description", "amount", "externalRef" }` | `{ "folio" }` |
| `POST /v1/chargebacks/prevent` | mismo cuerpo, `type = CHARGEBACK_PREVENTION` | `{ "folio" }` |
| `GET /v1/alerts/inbound?since=<ISO-8601>` | — | `{ "alerts": [ { "folio", "type", "bin", "last4", "merchantId", "merchantName", "description", "issuedAt", "source" } ] }` |

Tipos de alerta: `ENUMERATION`, `CONFIRMED_FRAUD`, `COMPROMISED_CARD`, `SUSPICIOUS_MERCHANT`, `CHARGEBACK_PREVENTION`, `OTHER`.

## 6. Casos de certificación

Los mismos que corre `scripts/verify_guild.py` contra el simulador y que se repiten contra el ambiente de pruebas del gremio:

1. Alerta saliente con folio; reintentos ante caída del canal; reenvío manual.
2. Consulta SVL: positivo bloquea la tarjeta en el autorizador (`59`, `GUILD_SVL_LISTED`); caché de 24 h; degradación con `fail-open`.
3. Alerta entrante de tarjeta comprometida: bloqueo automático y plazo de 10 días hábiles.
4. Alerta entrante de comercio sospechoso: lista de bloqueo del comercio.
5. Aviso SPC al enviar un contracargo.
6. Vencimiento del plazo: quebranto asumido y cierre imposible después.

## 7. Consola

`GET /api/guild/status`, `GET/POST /api/guild/alerts`, `POST /api/guild/alerts/{id}/send|close`,
`POST /api/guild/verify/card/{cardId}`, `POST /api/guild/verify/merchant/{merchantId}`,
`POST /api/guild/outbox/flush`, `POST /api/guild/inbound/poll`, `POST /api/guild/deadlines/run`.
En modo `simulated` existen además `POST /api/guild/simulator/*` para poblar listas, encolar alertas entrantes y apagar el gremio.
