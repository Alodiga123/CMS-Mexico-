# KYC del titular: alta de clientes con verificación

Ninguna tarjeta se emite a un cliente cuyo expediente KYC no esté **verificado**. El alta de un
cliente en la pestaña 5 de la consola (o por `POST /api/customers`) captura la identidad del
titular y corre en el acto las comprobaciones; el resultado queda en el expediente con la
evidencia de cada una, y decide lo que sigue:

| Resultado | Qué significa | Qué puede hacerse |
|---|---|---|
| `VERIFIED` | Todas las comprobaciones pasaron y no hay coincidencia en listas | Emitir tarjetas |
| `REVIEW` | Persona políticamente expuesta declarada, o el proveedor de listas no respondió | Un analista aprueba o rechaza con motivo escrito |
| `REJECTED` | Falló una comprobación dura o coincidió con una lista restringida | Corregir los datos y volver a verificar; una coincidencia en listas no se puede aprobar a mano |
| `PENDING` | Registrado sin datos de identidad | Completar la identidad (`PUT /api/customers/{id}`) |

## Lo que se captura

Nombre(s), apellidos, fecha de nacimiento, sexo, CURP, RFC, identificación (INE, pasaporte,
cédula profesional, FM2/FM3 o matrícula consular) con número y vigencia, correo, teléfono,
nacionalidad, ocupación, domicilio, código postal, estado y la declaración de persona
políticamente expuesta. En las listas la CURP y el RFC viajan enmascarados; el expediente
completo (`GET /api/customers/{id}/kyc`) los muestra a quien tiene permiso de lectura.

## Las comprobaciones

1. **CURP**: estructura de 18 caracteres, dígito verificador (algoritmo RENAPO), fecha de
   nacimiento, sexo y entidad, y correspondencia con el apellido paterno y el nombre (con la regla
   de José / María).
2. **RFC** de persona física: 13 caracteres, dígito verificador (algoritmo del SAT), fecha de
   nacimiento y apellido paterno.
3. **Edad**: mayor de 18 años.
4. **Documento**: tipo admitido, número completo y vigencia no vencida.
5. **Unicidad**: una sola persona por CURP en el CMS.
6. **Contacto**: correo y teléfono válidos.
7. **Listas restringidas** (OFAC, ONU, PLD nacional): consulta al proveedor configurado. Sin
   respuesta del proveedor el cliente **no** se verifica solo: va a revisión.
8. **PEP**: la declaración del titular manda el caso a un analista.

El riesgo queda en `LOW`, `MEDIUM` (PEP, revisión) o `HIGH` (coincidencia en listas).

## Proveedor de listas

`KYC_SCREENING_MODE=simulated` (por defecto) responde con una lista local de prueba: un nombre que
contenga `LISTA NEGRA`, `SANCIONADO` o `PRUEBA PLD` coincide (configurable con
`KYC_SCREENING_SIMULATED_HITS`). `KYC_SCREENING_MODE=http` consulta al proveedor real:
`POST KYC_SCREENING_URL` con `{fullName, curp, rfc, birthDate}` y cabecera `X-API-Key`
(`KYC_SCREENING_API_KEY`), esperando `{hit, lists[], score, detail}`. Está en el registro de
terceros como **KYC_SCREENING** con su salud y su contrato (pestaña 19).

## Revisión del analista

Desde el expediente (botón "Expediente KYC" en la lista de clientes) un usuario con permiso de
operación aprueba o rechaza un caso en `REVIEW` o `REJECTED`, siempre con motivo; la decisión
queda firmada con su usuario y fecha, y en la auditoría (`KYC_APPROVED`,
`KYC_REJECTED_BY_ANALYST`). "Volver a verificar" repite las comprobaciones, por ejemplo cuando el
proveedor de listas vuelve o tras corregir los datos.

## Emisión

`POST /api/cards/issue` rechaza con `422 KYC_NOT_VERIFIED` a cualquier cliente que no esté
`VERIFIED`; el selector de clientes de la pestaña 3 solo ofrece los verificados y muestra en gris
el motivo de los demás.

## Verificación

`python scripts/verify_kyc.py` con el CMS en 8085 y el proveedor simulado: titular limpio
verificado y con tarjeta; CURP inválida, menor de edad e identificación vencida rechazados y
corrección que reverifica; nombre en la lista rechazado con riesgo alto que el analista no puede
aprobar; PEP a revisión, sin tarjeta hasta que el analista aprueba con motivo; CURP duplicada
rechazada; registro sin identidad pendiente; auditoría y registro de terceros. Los demás scripts
`verify_*.py` registran sus clientes con `scripts/kyc_demo.py`, que genera CURP y RFC válidos y
consistentes con el nombre. Las reglas puras están en `CurpRfcTest` y `KycServiceTest`.
