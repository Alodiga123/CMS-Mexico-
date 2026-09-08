# RFP-FINSUS-TDC-CORP-2025 · Anexo B: matriz de cobertura

Cobertura del CMS Mexico y su autorizador frente a cada requerimiento del RFP de Finsus
para el core de lending de la tarjeta de crédito corporativa. Estados según la leyenda del
RFP: **N** nativo (disponible hoy sin desarrollo), **C** configurable (existe y se
parametriza), **D** desarrollo (hay que construirlo; se indica sobre qué base), **NA** no
aplica. Cuando un requerimiento tiene partes en distinto estado se declara el estado
dominante y se detallan las partes.

Base de evaluación: la plataforma desplegada en https://ac.alocashfintech.com el 8 de
septiembre de 2026 (emisión multiproducto, autorizador ISO 8583 con HSM y stand-in, fraude,
gremio, aclaraciones, plásticos, compensación y liquidación, conciliación, reportes,
terceros, seguridad con IAM).

## Resumen

| Bloque | N | C | D | NA | Lectura |
|---|---|---|---|---|---|
| RF-01 a RF-05 (estructura, líneas, restricciones, recomposición, estados) | 0 | 2 | 3 | 0 | Los controles y estados de tarjeta existen; la jerarquía corporativa es nueva |
| RF-06 a RF-08 (facturación, pagos, mora) | 0 | 0 | 3 | 0 | El core de lending propiamente dicho: el módulo a construir |
| RF-09 (reportería) | 0 | 1 | 0 | 0 | Motor de reportes sellados nativo; 4 de 16 reportes hoy, 12 por construir |
| RF-10 (APIs e integraciones) | 1 | 2 | 0 | 0 | REST, OpenAPI, clave de API y sandbox nativos; adaptador Pomelo por construir |
| RNF-01 a RNF-06 | 0 | 3 | 3 | 0 | Seguridad y regulatorio con base sólida; alta disponibilidad, PCI nivel 1 y multi-tenant por hacer |

Cobertura funcional estimada para el MVP del RFP (seis casos de uso): el 4 (bloqueo y
activación desde portal) y el 5 (cambio de límite en tiempo real) se cubren con lo
existente más el portal de autogestión; el 1, 2, 3 y 6 dependen del módulo de lending.

## Requerimientos funcionales

| ID | Requerimiento | Estado | Comentarios / notas |
|---|---|---|---|
| RF-01 | Gestión de estructura corporativa multinivel | **D** | Hoy el modelo es cliente → tarjetas, con controles y límites por tarjeta y por producto. Falta la jerarquía cuenta madre → empresa/área → usuario (hasta 4 niveles), herencia o límite propio por nivel, conglomerados, clonación de estructuras y el portal de autogestión del administrador corporativo. Se construye sobre el módulo de clientes y el de controles por tarjeta existentes; el portal reutiliza la consola (autenticación IAM, permisos, auditoría). |
| RF-02 | Gestión de líneas de crédito (múltiples líneas bajo contrato) | **D** (base C) | Existe la línea de crédito por tarjeta (producto CREDIT, límite y disponible en el autorizador, cambio de límite en tiempo real por API y consola, con auditoría). Falta: línea global en la cuenta madre con sublímites por nivel, varias líneas por contrato (compras, efectivo, marketing, promocional), extrafinanciamiento y ampliación masiva por lote. El historial de cambios ya queda en auditoría con usuario, fecha y detalle. |
| RF-03 | Restricciones por MCC, comercio, horario, día, canal, monto | **D** (base N) | Nativo hoy por tarjeta: canal (POS, cajero, internet, sin contacto), uso internacional, aviso de viaje, límite diario, semanal y mensual, y a nivel global listas de bloqueo de comercio y país. Por construir: inclusión/exclusión por MCC, lista blanca/negra de comercios por tarjeta y por nivel, horario y día de la semana, monto máximo por operación, número de operaciones por periodo, tarjetas virtuales de un solo uso y herencia de restricciones por nivel. El motor de reglas del autorizador ya evalúa controles antes de fondos, así que las nuevas reglas se agregan en el mismo punto. |
| RF-04 | Recomposición de disponible (diaria, semanal, mensual, por ciclo) | **C** (parte D) | Diaria, semanal y mensual son nativas: el disponible por ventana se recompone solo. Por construir: sin recomposición (un solo uso o crédito fijo) y recomposición al liquidar el corte, que depende del ciclo de facturación de RF-06. |
| RF-05 | Gestión de estados de tarjeta y cuenta | **C** (parte D) | Estados de tarjeta nativos con transiciones controladas: nueva sin entregar (04), activa (00), pausada (05), cancelada a solicitud o por el banco (06/07), con auditoría y efecto inmediato en el autorizador. Por construir los estados de cuenta ligados a la deuda: arreglo de pago (08), cobro administrativo (11) y cobro judicial (10), con sus transiciones. |
| RF-06 | Motor de facturación (ciclos, intereses, pago mínimo) | **D** | No existe. Es el núcleo del módulo de lending: calendarios de corte, intereses corrientes, sobre saldo y moratorios, tasa fija o referida a TIIE, pago de contado, mínimo (porcentaje, monto fijo o el mayor), revolvente y MSI, estado de cuenta madre y por usuario con desglose por área y MCC, envío por correo con registro (la mensajería y el sello de archivos existentes se reutilizan), archivo contable de salida y FECI. El ledger de doble partida actual es la base contable. |
| RF-07 | Gestión de pagos con distribución a cuentas hija | **D** | Hoy solo hay abonos a nivel de tarjeta (recarga y depósito en el core). Por construir: pago en la cuenta madre con distribución automática a hijas por política (saldo, fecha, tipo), pagos parciales con orden de aplicación configurable, conciliación automática de SPEI, ventanilla y cargo automático, historial y alerta previa a la fecha límite (la alerta reutiliza la mensajería). La conciliación con el core bancario existente sirve de base para la de pagos. |
| RF-08 | Gestión de mora, aging, arreglos de pago y cobranza | **D** | No existe. Aging por rangos, clasificación de cartera, reestructuras, intereses moratorios por estado, sobregiros, canceladas por mora y exportación a despachos. Se construye sobre el motor de facturación. |
| RF-09 | Reportería operativa y contable (16 reportes) | **C** (12 D) | El motor de reportes es nativo: rango de fechas, vista previa, corrida guardada con CSV sellado con SHA-256 y verificación de integridad, descargables desde la consola. Cubiertos hoy: listado de tarjetas, autorizaciones denegadas (con razón, monto y comercio), tarjetas pendientes de entrega y cambios en límites (auditoría). Los 12 restantes (saldos deudores y acreedores, movimientos, resumen por tipo, listado de cuentas, cartera por mora, clasificación A-E, intereses diarios, pagos a cuentas hija, cuentas sin movimiento, sobregiradas, consumos por área, estado de cuenta corporativo) se agregan al mismo motor conforme exista el lending; salida XLSX y PDF por construir (hoy CSV). |
| RF-10.1 | API de integración con el procesador de tarjetas (Pomelo) | **D** (base N) | Nativo: autorizador con respuesta ISO campo 39 por REST y por canal ISO 8583, consulta de disponible, stand-in con límites, estados de tarjeta por API, controles por tarjeta por API y gestión de PIN y CVV en HSM (PVV, sin almacenar el PIN). Por construir: el adaptador al contrato de Pomelo (consulta de disponible en su formato, recepción de transacciones aprobadas y rechazadas, sincronización de estados, PIN y restricciones) y su certificación. |
| RF-10.2 | API de integración con sistemas internos de Finsus | **C** (parte D) | Core bancario: nativo con Mifos X / Fineract (retención, captura, liberación, depósito y conciliación); otro core requiere un adaptador sobre el mismo puerto. Contable / ERP: exportación CSV sellada nativa; layout específico del ERP por construir. Fraude: motor propio y gremio nativos; envío de eventos a Sentinel por construir sobre el puerto de terceros. Conciliación Simetrik: exportación CSV nativa; layout por acordar. Notificaciones: puerto de mensajería nativo (SMS y WhatsApp, simulado o HTTP); webhooks de eventos por construir. |
| RF-10.3 | Estándares REST, OAuth 2.0, OpenAPI 3.0, webhooks | **C** (parte D) | Nativo: REST sobre HTTPS, OpenAPI 3.0 con Swagger, clave de API con rotación por configuración, autenticación de usuarios por token del IAM, sandbox completo con simuladores de core, HSM, gremio, mensajería y red. Por construir: emisión de tokens OAuth 2.0 (client credentials) para terceros, webhooks configurables de ciclo de vida y política formal de versionado con 12 meses de retrocompatibilidad. |

## Requerimientos no funcionales

| ID | Requerimiento | Estado | Comentarios / notas |
|---|---|---|---|
| RNF-01 | Disponibilidad ≥ 99.9 % mensual, RTO ≤ 2 h, RPO ≤ 15 min | **D** | La aplicación es sin estado y admite varias instancias; hoy corre en un solo nodo. Se requiere despliegue activo/pasivo o activo/activo con Postgres gestionado (réplica y respaldos continuos), balanceo y runbooks. El stand-in del autorizador cubre la caída del core sin detener la operación. |
| RNF-02 | Latencia de consulta de disponible ≤ 200 ms (P99) | **C** | El autorizador resuelve en memoria y ledger local en pocos milisegundos; con saldo en el core depende de la latencia de Mifos. Falta la prueba de carga formal a 5,000 y 50,000 cuentas y el ajuste de pool y caché. |
| RNF-03 | Seguridad: PCI DSS nivel 1, cifrado, tokenización, RBAC, auditoría, secretos, pentest | **C** (certificación D) | Nativo: PAN cifrado con AES-256-GCM y huella HMAC, PIN nunca almacenado (PVV en HSM), CVV en HSM, TLS 1.2+ en el borde, RBAC con permisos por rol vía IAM, auditoría de toda operación sensible con el usuario real, secretos por variables de entorno y rotación de clave de API. Por hacer: certificación PCI DSS nivel 1 (plan, QSA, segmentación), auditoría con sellado encadenado, gestión automatizada de certificados y pruebas de penetración anuales. |
| RNF-04 | Escalabilidad a 50,000+ cuentas; multi-tenant; ambientes separados | **D** (base C) | Monolito modular escalable horizontalmente con Postgres; ambientes se separan por configuración (hoy desarrollo y demo). Por hacer: aislamiento multi-tenant por emisor, ambiente UAT de certificación y hoja de ruta de capacidad documentada. |
| RNF-05 | Cumplimiento regulatorio Banxico / CNBV / CONDUSEF | **C** (parte D) | Nativo para tarjetas: serie R24 de Banxico, reporte PLD/UIF y actividad de aclaraciones para CONDUSEF, con corridas selladas. Por construir: parámetros y reportes propios del crédito (CAT, comisiones y términos según circulares, cartera para CNBV como SOFOM ENR). |
| RNF-06 | Mantenibilidad y soporte: documentación, manuales, SLA, actualizaciones, capacitación | **C** (entregables D) | Existe documentación técnica por módulo (seguridad, ISO 8583 y HSM, gremio, compensación, terceros), OpenAPI, guion de demostración y pruebas automáticas de extremo a extremo por módulo. Por entregar: manual de operación y de usuario del backoffice, SLA contractual por severidad, política de actualizaciones regulatorias y plan de capacitación de 40 horas. |

## Casos de uso del MVP

| # | Caso de uso | Cobertura hoy | Depende de |
|---|---|---|---|
| 1 | Tarjeta corporativa PyME con cuenta tesorera y sublímites | Emisión, controles y límites por tarjeta | RF-01, RF-02, RF-03 (MCC), RF-06 |
| 2 | Pago total con distribución a cuentas hija | Abonos por tarjeta y conciliación con el core | RF-07 |
| 3 | Cierre de ciclo con intereses, pago mínimo y estado de cuenta | Ledger, mensajería y sello de archivos | RF-06 |
| 4 | Bloqueo y activación desde el portal del cliente | Estados de tarjeta por API con efecto inmediato en el autorizador | Portal de autogestión (RF-01) |
| 5 | Cambio de límite de área en tiempo real | Cambio de límite por tarjeta en tiempo real | Niveles de la jerarquía (RF-01, RF-02) |
| 6 | Reporte de consumos por área, empleado y MCC | Motor de reportes sellados | Jerarquía y captura de MCC (RF-01, RF-09) |

## Notas para la propuesta

- **Lo que aportamos desde el día uno**: emisión y ciclo de vida de tarjetas, autorizador
  multiproducto con ISO 8583, HSM y stand-in, fraude en línea y gremio, aclaraciones,
  plásticos, compensación y liquidación, conciliación con el core, reportes sellados,
  seguridad con IAM y auditoría, y un sandbox completo con simuladores.
- **Lo que se construye**: el core de lending (jerarquía corporativa, líneas por nivel,
  facturación, pagos distribuidos, mora y cobranza), las restricciones finas por MCC,
  horario y comercio, el adaptador a Pomelo y los reportes de cartera. Todo sobre los
  puertos y módulos existentes, sin reemplazar lo que ya funciona.
- **Fuera del alcance del RFP** y ya existente en la plataforma, por si se quiere ofrecer
  como opcional: el autorizador y switch (hoy lo cubre Pomelo), la emisión de plásticos y
  la personalización con bureau.
