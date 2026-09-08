# Contratos con terceros

Emitir tarjetas no se hace solo. El CMS se apoya en doce terceros; este documento dice, para
cada uno, qué hace por nosotros, cómo está conectado hoy, qué hay que contratar y certificar
antes de salir a producción, y qué variables lo cambian de simulado a real. La pestaña
**19. Terceros & Contratos** de la consola muestra lo mismo en vivo: si responde ahora, qué
variables están puestas y dónde va el contrato.

## Cómo está construido

- **Un registro en código** (`ThirdPartyRegistry.CATALOG`): clave, nombre, para qué sirve,
  tipo de conexión (`ONLINE`, `FILE`, `MANUAL`, `NONE`), proveedor por defecto, variables y
  lista de certificación.
- **Un contrato por tercero en base de datos** (`third_party_contracts`): proveedor,
  referencia, estado (`PENDING → NEGOTIATION → SIGNED → CERTIFYING → ACTIVE`, más `SUSPENDED`
  y `TERMINATED`), fechas de firma y vencimiento, contacto, niveles de servicio y la lista de
  certificación con `[x]` / `[ ]`. Se siembran en `PENDING` al arrancar; los edita
  administración (`CMS:ADMIN`) y cada cambio queda en auditoría.
- **Salud en vivo**: `GET /api/thirdparties` responde rápido con lo que ya se sabe;
  `POST /api/thirdparties/{key}/check` hace la llamada real (core, IAM, HSM, gremio,
  mensajería).
- **Puertos con dos implementaciones**: para cada conexión en línea hay un simulador (el que
  corre en desarrollo y en las pruebas) y un cliente real, elegidos por una variable de modo.
  El negocio no distingue cuál tiene detrás.

## Los terceros

| Clave | Tercero | Conexión hoy | Modo real |
|---|---|---|---|
| `CORE_BANKING` | Core bancario Mifos X / Fineract | en línea (REST) | `CORE_MODE=fineract` |
| `HSM` | payShield 10K | en línea (comandos de host, TCP 1500) | `HSM_HOST/HSM_PORT` + llaves bajo LMK |
| `IAM` | IAM corporativo | en línea (introspección de tokens) | `IAM_BASE_URL` |
| `GUILD` | antifraude del gremio (SVL / SNA / SPC) | en línea (REST + mTLS + HMAC) | `GUILD_MODE=http` |
| `MESSAGING` | SMS / WhatsApp al tarjetahabiente | en línea (REST) | `THIRDPARTY_MESSAGING_MODE=http` |
| `PERSO_BUREAU` | bureau de personalización | archivo de emboce cifrado | `PLASTICS_MANUFACTURER_KEY` propia |
| `COURIER` | mensajería de plásticos | manual (guía al despachar) | — |
| `NETWORK_VISA` | Visa | ISO 8583 en línea + archivo de compensación | licencia + certificación |
| `NETWORK_MASTERCARD` | Mastercard | ISO 8583 en línea + archivo de compensación | licencia + certificación |
| `NETWORK_DOMESTIC` | switch nacional | ISO 8583 en línea + archivo de compensación | convenio + certificación |
| `TREASURY_SPEI` | banco liquidador | manual (referencia SPEI al pagar el ciclo) | — |
| `CREDIT_BUREAU` | buró de crédito | sin integrar | pendiente |

### Core bancario (Mifos X / Fineract)

Guarda el saldo de las tarjetas de débito y la línea de crédito; el CMS retiene, captura,
libera y deposita contra él. Sin core, el autorizador entra en stand-in
([iso8583-criptografia-standin.md](iso8583-criptografia-standin.md)).

Contratar y certificar: usuario de servicio con permisos mínimos, tenant y oficina de
producción, producto de ahorro y tipo de pago para tarjetas, pruebas de retención / captura /
liberación, prueba de indisponibilidad con liquidación posterior, ventana de mantenimiento y
guardia. Variables: `CORE_MODE`, `FINERACT_URL`, `FINERACT_TENANT`, `FINERACT_USER`,
`FINERACT_PASSWORD`.

### HSM (payShield 10K)

PIN (PVV), CVV/CVV2, ARQC/ARPC y las llaves de zona con la red. En desarrollo corre el
simulador PayShieldSim con llaves de desarrollo que **no sirven para producción**.

Contratar y certificar: equipo propio o servicio hospedado con LMK y ceremonia de llaves,
generación de PVK / CVK / IMK, intercambio de ZPK / ZMK con la red y los adquirentes, comandos
habilitados (BA, DG, EA, CW, CY, KQ, NC), alta disponibilidad, respaldo del LMK, PCI PIN.
Variables: `HSM_HOST`, `HSM_PORT`, `HSM_KEY_PVK`, `HSM_KEY_CVK_A`, `HSM_KEY_CVK_B`,
`HSM_KEY_IMK`, `HSM_KEY_ZPK`.

### IAM corporativo

Quién entra a la consola y con qué permisos ([seguridad-iam.md](seguridad-iam.md)).
Contratar y certificar: proyecto `EMISION_CMS` y permisos registrados, los cuatro roles,
rotación de la clave de API de sistemas, bitácora de accesos 12 meses. Variables:
`IAM_BASE_URL`, `IAM_PROJECT_CODE`, `SECURITY_ENABLED`.

### Antifraude del gremio (ACT-094)

Listas de tarjetas y comercios comprometidos, alertas en ambos sentidos, aviso temprano de
aclaraciones ([guild-antifraude.md](guild-antifraude.md)). Contratar y certificar: convenio
de participación y clave, certificado mTLS y secreto HMAC, pruebas SVL / SNA / SPC, plazos de
cierre (10 días hábiles) y de respuesta a enumeración (5). Variables: `GUILD_MODE`,
`GUILD_PARTICIPANT_ID`, `GUILD_BASE_URL`, `GUILD_API_KEY`, `GUILD_HMAC_SECRET`,
`GUILD_SSL_BUNDLE`.

### Mensajería al tarjetahabiente (SMS / WhatsApp)

El CMS envía cuatro cosas: el código de un solo uso de la verificación reforzada, el aviso de
que el plástico va en camino (con guía), el aviso de entrega y el resultado de una
aclaración. Nunca habla con una operadora: le entrega el mensaje a un proveedor contratado.

**Puerto** `MessagingProvider` con dos implementaciones:

- `SimulatedMessagingProvider` (por defecto): entrega al instante, guarda los últimos
  mensajes en memoria y se puede apagar (`POST /api/thirdparties/messaging/simulator
  {down:true}`) para ensayar una caída.
- `HttpMessagingProvider` (`THIRDPARTY_MESSAGING_MODE=http`): el contrato REST al que se
  mapea cualquier proveedor (Twilio, Infobip, MessageBird, un agregador local) con un
  adaptador fino:

```
POST {THIRDPARTY_MESSAGING_URL}/messages
Authorization: Bearer {THIRDPARTY_MESSAGING_API_KEY}
{ "channel": "SMS|WHATSAPP|EMAIL", "to": "5512345678", "text": "...", "ref": "STEPUP", "sender": "CMS-MX" }
→ 2xx { "id": "<referencia del proveedor>", "status": "SENT|DELIVERED" }
GET  {THIRDPARTY_MESSAGING_URL}/health   → 2xx si está arriba
```

**Bandeja de salida** (`outbound_messages`): cada mensaje se escribe antes de enviarse, con
destinatario, plantilla, texto (el código de un solo uso va **enmascarado** en nuestra copia,
`thirdparty.messaging.mask-otp=true`), estado (`QUEUED`, `SENT`, `DELIVERED`, `FAILED`),
referencia y respuesta del proveedor, intentos y último error. Si el proveedor falla, el
mensaje queda `QUEUED` y el trabajo de reintento lo vuelve a mandar cada 30 s hasta 5 veces;
un código enmascarado no se reenvía (el cliente pide un reto nuevo). Los procesos de negocio
nunca fallan porque un mensaje no salió: registran y siguen.

Contratar y certificar: remitente registrado (short code o WhatsApp Business verificado),
plantillas aprobadas (OTP, tarjeta enviada, tarjeta entregada, aclaración resuelta), entrega
de OTP en menos de 10 s, reporte de entrega (DLR) y reintentos, contrato de encargado de
tratamiento de datos personales. Variables: `THIRDPARTY_MESSAGING_MODE`,
`THIRDPARTY_MESSAGING_URL`, `THIRDPARTY_MESSAGING_API_KEY`, `THIRDPARTY_MESSAGING_SENDER`,
`THIRDPARTY_MESSAGING_CHANNEL`, `THIRDPARTY_MESSAGING_MAX_ATTEMPTS`,
`THIRDPARTY_MESSAGING_RETRY_MS`, `THIRDPARTY_MESSAGING_MASK_OTP`.

### Portal de negocios (adquirencia)

El padrón de comercios afiliados vive en el backend de adquirencia
(`puntos-adquisicion-pos/BackendAdquiriencia`, `GET /api/v1/merchants`). El CMS lo lee para
alimentar el selector de comercio de las dos pantallas de autorización (pestañas 6 y 11): al
elegir un comercio se llenan el nombre y el código de afiliación que viajan como campos 43 y
42 del mensaje. En producción esos campos los trae el adquirente en el ISO 8583; el selector
solo sirve para simular desde la consola.

- **Puerto** `MerchantDirectoryClient`: `GET {MERCHANT_PORTAL_URL}/merchants` con la
  cabecera `X-API-Key`; respuesta con `data[]` (`codigoComercio`, `nombreComercio`, `rfc`,
  `estatus`, `bloqueado`, `tipoComercio`, `subMid`). El listado se guarda en caché 60 s. Si el
  portal no responde, se sirve el último listado bueno y la consola lo avisa; si no hay
  credencial, el selector queda en captura manual. Nunca bloquea una autorización.
- **Endpoint del CMS**: `GET /api/merchants?search=&activeOnly=true` (`CMS:READ`) devuelve
  `available`, `detail` y la lista filtrada; el registro de terceros lo muestra como
  `MERCHANT_PORTAL` con verificación en vivo.
- **Credencial**: en el portal, un administrador emite una credencial de integración con rol
  `COMERCIOS` (`POST /api/v1/agregadores/credenciales/integracion`
  `{"nombre":"CMS Mexico","rolOtorgado":"COMERCIOS"}`); el secreto solo se muestra una vez y
  va en `MERCHANT_PORTAL_API_KEY`. El portal debe aceptar la clave en la ruta `/merchants`
  (`RUTAS_COEXISTENTES` de su `ApiKeyAuthenticationFilter`).

Contratar y certificar: credencial con rol `COMERCIOS`, ruta `/merchants` habilitada para la
clave, acuerdo de uso del padrón (incluye RFC), sincronización de altas y bajas. Variables:
`MERCHANT_PORTAL_URL`, `MERCHANT_PORTAL_API_KEY`, `MERCHANT_PORTAL_TIMEOUT_MS`,
`MERCHANT_PORTAL_CACHE_SECONDS`, `MERCHANT_PORTAL_ENABLED`.

### Bureau de personalización (plásticos)

Recibe el archivo de emboce sellado y cifrado con AES, produce y embarca los plásticos y el
PIN mailer. Contratar y certificar: llave AES intercambiada en ceremonia, perfil de chip
aprobado por la red, canal SFTP y ventana de corte, prueba de lote completo, PCI Card
Production. Variables: `PLASTICS_MANUFACTURER`, `PLASTICS_MANUFACTURER_KEY` (la de
`application.properties` es de desarrollo y el registro lo avisa), `PLASTICS_CHIP_PROFILE`.

### Mensajería de plásticos

Lleva el plástico al domicilio. Hoy es manual: la guía se captura al despachar y la entrega
se confirma a mano; el cliente recibe ambos avisos por mensajería. Contratar: guía y
confirmación de entrega, devoluciones y destrucción de no entregados, seguro y cadena de
custodia, rastreo por API (opcional).

### Redes (Visa, Mastercard, switch nacional)

Autorización en línea por ISO 8583 y compensación por archivo
([compensacion-liquidacion.md](compensacion-liquidacion.md)). Contratar y certificar:
licencia de emisor y BIN, conexión directa o por procesador, certificación de autorización,
certificación de compensación y liquidación, llaves de zona y CVV / PVV con el HSM, programa
de disputas (VROL / Mastercom). El adaptador de formato de cada red (BASE II, IPM) a CMS-CLR
es parte de la certificación.

### Banco liquidador (SPEI)

Paga o cobra la posición neta de cada ciclo; la referencia SPEI se captura al marcar el ciclo
como pagado. Contratar: cuenta de liquidación por red, conciliación bancaria del neto contra
el archivo sellado.

### Buró de crédito

Consulta de historial para la línea de crédito y reporte mensual de la cartera. **Sin
integrar**: el registro lo muestra como pendiente. Contratar: consulta y reporte,
consentimiento del cliente (NIP) en el alta, formato de reporte mensual.

## API

| Método | Ruta | Permiso |
|---|---|---|
| `GET` | `/api/thirdparties`, `/api/thirdparties/{key}` | `CMS:READ` |
| `POST` | `/api/thirdparties/{key}/check` | `CMS:OPERATE` |
| `PUT` | `/api/thirdparties/{key}/contract` | `CMS:ADMIN` |
| `GET` | `/api/thirdparties/messaging/status`, `/api/thirdparties/messages?status&cardId&page&size`, `/messages/{id}` | `CMS:READ` |
| `POST` | `/api/thirdparties/messages/test`, `/messages/{id}/retry`, `/messaging/simulator` | `CMS:OPERATE` |

## Verificación

`python scripts/verify_thirdparties.py`: 36 comprobaciones. El registro con sus doce terceros,
salud y variables sin exponer valores; verificación en vivo de HSM, core e IAM; edición de
contratos con estados, vencimiento y auditoría; el código de un solo uso viaja por el
proveedor y queda enmascarado en nuestra copia; avisos de envío y entrega del plástico y de
la aclaración resuelta; caída del proveedor con mensaje en cola, el autorizador sigue
respondiendo, reintento manual y paginación de la bandeja. Borra sus datos al terminar.
