# Demostración del CMS y el autorizador, paso a paso

Guion para mostrar, en una sola sesión, todo lo que hace el CMS Mexico y su autorizador,
en el mismo orden en que lo probamos. Dura unos 45 minutos si se recorre completo.

**Dónde:** https://ac.alocashfintech.com (o http://localhost:8085 en local). El portal del
adquirente (terminales, ventas on-us contra nuestras tarjetas) está en
https://ac.alocashfintech.com/pos/login con `mesa@finsus.mx`; su **POS virtual** (Motor de
Adquirencia → POS virtual) cobra con el PAN de una tarjeta emitida aquí y la autorización llega a
este CMS; ver [adquirencia-onus.md](adquirencia-onus.md).
**Entrar con:** `admin.cms` / `AdminCms.2026!` (acceso total). Otros perfiles para mostrar
la separación de funciones: `mesa.cms`, `fraude.cms`, `auditor.cms` (contraseñas en
[seguridad-iam.md](seguridad-iam.md)).

Antes de empezar, verifica en la pestaña **19. Terceros & Contratos** que el core, el HSM,
el IAM, el gremio y la mensajería aparecen como "responde". Si el core marca caído, espera
15 segundos y pulsa "Verificar": el CMS entra en stand-in y vuelve solo.

---

## 1. Producto y emisión (pestañas 1, 2, 3)

1. **Crear el producto** (pestaña 1). Elige la plantilla "Tarjeta Alocash Prepago" o llena
   a mano: nombre, tipo PREPAGO, red VISA, BIN `453213`, moneda MXN, país MX. Recorre los
   cuatro pasos (datos, apariencia con color y logo, límites y tarifas, criptografía y
   promoción) y guarda. Muestra la ficha de BIN (emisor, oficina, jurisdicción) y el
   catálogo con "Abrir catálogo de tarjetas creadas".
2. **Emitir a un cliente** (pestaña 2). Selecciona el producto nuevo y un cliente (o crea
   uno en la pestaña 5 con nombre y teléfono). Nombre estampado, categoría física, "Auto"
   para los últimos cuatro, depósito inicial 1,000, y "Emitir y cifrar con HSM". La vista
   previa de la derecha cambia en vivo.
3. **Ver la tarjeta** (pestaña 3). Filtra por el producto; en la fila, "Detalles HSM" abre el
   PVV, el CVV2 y las llaves con las que se cifró; el selector de acciones lleva a
   suspender, bloquear o a "Ver tarjeta 360".

Qué contar: el PAN va cifrado (AES-256-GCM) y con huella HMAC en la bóveda; el PIN nunca se
guarda, solo su PVV calculado en el HSM; el CVV y el CVV2 salen del HSM.

## 2. Tarjeta 360 y controles (pestaña 10)

Busca la tarjeta por id o últimos cuatro. Muestra el encabezado (estado, vencimiento,
saldo con su origen: ledger, línea o core), los **controles por tarjeta** (apagar compras
por internet, uso internacional, aviso de viaje, límites propios que ganan a los del
producto), la cuenta del core, las autorizaciones, los plásticos y las aclaraciones.
Prueba a desmarcar "Compras por internet" y guardar: la siguiente autorización e-commerce
se declina con código 57.

## 3. Autorizar, capturar y reversar (pestaña 11)

1. Elige la tarjeta, monto 150, canal POS, comercio "Cafetería Centro". **Autorizar**:
   respuesta `00`, código de autorización, disponible después, retención HELD, riesgo 0.
   La tabla "Intentos de la tarjeta" se actualiza.
2. Repite con un monto mayor al disponible: `51` fondos insuficientes. Con la tarjeta
   suspendida: `54/62` según el estado. Cada código sigue el campo 39 de ISO 8583.
3. Pega el código en "Retención: capturar o reversar" y **Captura** (total o parcial). La
   retención pasa a CAPTURED y el dinero se mueve: en prepago sale del ledger, en débito
   con saldo en el core sale de Mifos.
4. Autoriza otra compra y **Reversa**: la retención se libera y el disponible vuelve.
5. Explica el panel superior: canal ISO 8583 escuchando en 8583 para los adquirentes,
   HSM y core arriba, y el **stand-in**: si el core no responde, el CMS aprueba dentro de
   sus límites (2,000 por operación, 5 al día) y asienta después con "Asentar stand-in".

Para enseñar el stand-in en vivo: `POST /api/core/outage {"down":true}` con la clave de API
marca el core caído; autoriza (queda "stand-in pendiente"), vuelve a levantar el core y
pulsa "Asentar stand-in en el core".

## 4. Fraude en línea y verificación reforzada (pestaña 15)

1. Con una tarjeta con poco saldo, autoriza tres compras de 100 en POS (tres `51`). La
   cuarta, en canal **e-commerce**, responde `1A`: el motor pide verificación reforzada por
   "tres declinaciones en 30 minutos".
2. El código de un solo uso viaja por la mensajería (pestaña 19, solapa "Mensajería al
   cliente", donde se ve el envío con el código enmascarado). En la demo el código también
   aparece en el panel del autorizador ("OTP de prueba"). Escríbelo y pulsa "Verificar y
   reintentar": la compra se aprueba con `STEP_UP_VERIFIED`. El mismo token no vale dos veces.
3. En la pestaña 15 muestra las **alertas** (declinadas, verificación reforzada,
   enumeración) con su puntaje y motivos, y el selector de acción por alerta: revisar,
   descartar, bloquear comercio o tarjeta.
4. **Listas de bloqueo**: bloquea un comercio a mano y autoriza contra él: `59` con
   `BLOCKLIST_MERCHANT` y puntaje 100.
5. **Retos emitidos**: la lista de todas las verificaciones reforzadas con su estado.

## 5. Antifraude de industria, el gremio (pestaña 16)

1. Estado del canal (simulado en la demo), participante y plazos.
2. **Verificaciones SVL**: verifica una tarjeta y un comercio; limpio o listado, con folio.
   En el simulador (cuarta solapa) lista una tarjeta y vuelve a verificar: entra a la lista
   de bloqueo local y la siguiente autorización se declina con `GUILD_SVL_LISTED`.
3. **Alertas** en ambos sentidos: levanta una alerta de fraude confirmado (sale al outbox y
   el gremio devuelve folio); encola una alerta entrante desde el simulador y muestra cómo
   el CMS bloquea la tarjeta solo. Cierra una alerta y enseña los plazos (10 días hábiles).
4. **Aviso temprano de contracargo (SPC)**: se dispara solo al enviar un contracargo en la
   pestaña 14.

## 6. Aclaraciones y contracargos (pestaña 14)

1. **Abrir aclaración** sobre una compra capturada: tarjeta, código de autorización, razón
   (por ejemplo 13.1 mercancía no recibida), descripción, abono provisional sí/no.
2. En el detalle: plazos de contracargo, representación y resolución; **adjuntar
   evidencia** (queda sellada con SHA-256); **enviar contracargo** (pide el caso del
   adquirente y avisa al gremio); **resolver** a favor del cliente o del comercio. El
   cliente recibe el resultado por mensajería.
3. El **expediente** se descarga con todo lo anterior. El filtro "Vencen en 7 días" muestra
   lo urgente.

## 7. Plásticos y bureau (pestaña 13)

1. Pide un plástico para una tarjeta (motivo NEW, dirección, PIN mailer).
2. **Armar lote con los pedidos**: se genera el archivo de emboce, sellado y cifrado
   (AES-256-GCM) con PVV y CVV2 del HSM; descárgalo y muestra que va cifrado.
3. Enviar al bureau, "Reporte del bureau" (producidos y fallidos), y en cada plástico el
   selector: enviar con mensajería y guía, entregado, activar (la tarjeta pasa a ACTIVE),
   devuelto, destruir, reponer. El cliente recibe el aviso de envío con la guía y el de
   entrega. "Pedir renovaciones" busca las que vencen en 60 días.

## 8. Compensación y liquidación (pestaña 18)

1. Con retenciones vivas (autoriza dos compras sin capturar), pulsa **Simular ciclo de la
   red** con red VISA y anomalías `over,duplicate,unknown,reversal,chargeback,fee`.
2. En el detalle del lote, línea por línea: `MATCHED_CAPTURED` (casó y capturó),
   `AMOUNT_MISMATCH` (llegó por más de la tolerancia: captura lo retenido y abre
   excepción), `ALREADY_CAPTURED` (duplicado), `NO_CARD` (PAN ajeno), `REVERSED`,
   `DISPUTE_LINKED` (contracargo casado con la aclaración), `FEE_BOOKED` (cuota de red).
3. Solapa **Excepciones**: lo que no cuadró, con enlace al lote y a la tarjeta; cada una es
   también un ítem en la pestaña 12.
4. Solapa **Liquidación por ciclo**: posición neta = presentaciones − reversos −
   contracargos − intercambio + cuotas de red. **Cerrar ciclo** (sella el CSV para
   tesorería, descargable), **Registrar pago** con la referencia SPEI. Un archivo tardío
   para un ciclo cerrado se rechaza.
5. Si hay un archivo real, "Cargar y procesar" acepta el formato CMS-CLR 1.0; el mismo
   archivo dos veces se rechaza por su sello.

## 9. Conciliación con el core (pestaña 12)

**Conciliar ahora**: compara las retenciones y capturas del CMS contra Mifos cuenta por
cuenta y lista las diferencias (retención que uno tiene y el otro no, montos distintos,
débitos en el core sin captura). "Resolver" con nota cierra cada una.

## 10. Reportes y regulatorio (pestaña 17)

Elige el rango y "Ver" para la vista previa con totales, o "Correr y sellar" para dejar la
corrida guardada con su CSV y sello SHA-256. Muestra el corte diario de emisión, la
conciliación con procesador, la serie R24 de Banxico, el reporte PLD/UIF, la actividad de
aclaraciones (Condusef) y las tarjetas inactivas. En "Corridas selladas", **Verificar sello**
prueba que el archivo guardado no fue alterado.

## 11. Contabilidad y auditoría (pestañas 7 y 8)

- **Contabilidad**: estado de cuenta del ledger por tarjeta y fechas, con totales de abonos,
  cargos y saldo.
- **Auditoría**: cada acción con quién y cuándo, de la más reciente a la más antigua, con
  filtros por entidad, acción, usuario e id. Busca la aclaración o el ciclo que acabas de
  tocar y muestra su rastro.

## 12. Terceros y contratos (pestaña 19)

Los doce terceros de la emisión: qué hace cada uno, cómo está conectado (en línea, por
archivo, manual, sin integrar), si responde ahora ("Verificar" hace la llamada real), qué
variables lo cambian de simulado a real y el contrato con su lista de certificación.
Edita el contrato de mensajería (estado, vencimiento, puntos cumplidos) y muestra que queda
en auditoría. La solapa de mensajería enseña la bandeja de salida, un mensaje de prueba y
la caída del proveedor con reintento.

## 13. Seguridad (opcional, 5 minutos)

Sal y entra como `auditor.cms`: solo lectura y reportes; los botones de operación
desaparecen y la API responde 403. Como `fraude.cms`: fraude y gremio, nada de productos.
Todo lo anterior queda en la auditoría con el usuario real del IAM.

---

## Preparación previa recomendada

- Tener a mano una tarjeta prepago con saldo (emitida en el paso 1) y una segunda con poco
  saldo para el paso 4.
- Si el servidor lleva tiempo sin uso, abre primero la pestaña 19 y pulsa "Verificar" en el
  core y el HSM.
- Los datos de la demo se pueden borrar al final desde SQL o dejar como historia; nada de
  lo anterior toca clientes reales del core salvo las cuentas de prueba.

## Referencias

- [seguridad-iam.md](seguridad-iam.md): usuarios, roles y permisos.
- [iso8583-criptografia-standin.md](iso8583-criptografia-standin.md): canal ISO, HSM y stand-in.
- [guild-antifraude.md](guild-antifraude.md): gremio SVL / SNA / SPC.
- [compensacion-liquidacion.md](compensacion-liquidacion.md): archivo CMS-CLR, casado y ciclos.
- [contratos-terceros.md](contratos-terceros.md): qué contratar con cada tercero.
