# Seguridad de la consola y la API con el IAM

La consola y la API del CMS ya no están abiertas. Las personas entran con sus credenciales del IAM
corporativo (repositorio AIM, puerto 4003) y los sistemas con una llave de API.

## Cómo funciona

1. La consola muestra la pantalla de inicio de sesión y llama a `POST /api/auth/login`. El CMS
   reenvía usuario y contraseña al IAM, recibe el token y lo introspecciona para el proyecto
   `EMISION_CMS`; devuelve al navegador el token, los roles y los permisos del CMS.
2. Cada llamada a `/api/**` lleva `Authorization: Bearer <token>`. Un filtro pide al IAM la
   introspección del token (con caché de 60 segundos) y construye la identidad: permisos
   `CMS:*` como autoridades y roles como `ROLE_*`. Un `SUPER_ADMIN` del IAM tiene todos los permisos.
3. Los sistemas (scripts, el conmutador mientras no exista canal ISO) mandan `X-Api-Key` con el
   valor de `CMS_API_KEY`. Se identifican como `CMS_API_KEY_NAME` con todos los permisos.
4. La auditoría registra siempre a la persona autenticada, sin importar lo que declare el cuerpo de
   la petición. Un sistema registra al operador que declara, o su propio nombre.
5. Sin credenciales: `401 AUTH_REQUIRED`. Token vencido o revocado: `401 AUTH_TOKEN_INVALID`.
   Usuario sin acceso al proyecto: `403 AUTH_NO_PROJECT_ACCESS`. Sin permiso: `403 AUTH_FORBIDDEN`.

## Permisos y roles registrados en el IAM (`scripts/iam_register_cms.py`)

| Permiso | Qué permite |
|---|---|
| `CMS:READ` | Consultar todo |
| `CMS:OPERATE` | Emitir, bloquear, controles, plásticos, retenciones, ledger, conciliación |
| `CMS:FRAUD` | Alertas, listas de bloqueo, verificación reforzada, gremio |
| `CMS:DISPUTES` | Aclaraciones y contracargos |
| `CMS:REPORTS` | Correr y sellar reportes |
| `CMS:ADMIN` | Productos, promociones, llaves HSM |

| Rol | Permisos | Usuario de demostración |
|---|---|---|
| `CMS_MESA_CONTROL` | READ, OPERATE, DISPUTES | `mesa.cms` / `Mesa.2026!` |
| `CMS_ANALISTA_FRAUDE` | READ, FRAUD | `fraude.cms` / `Fraude.2026!` |
| `CMS_AUDITOR` | READ, REPORTS | `auditor.cms` / `Auditor.2026!` |
| `CMS_ADMIN` | todos | `admin.cms` / `AdminCms.2026!` |

El administrador del IAM (`admin`) entra como `SUPER_ADMIN`. Las contraseñas de demostración son
solo para el ambiente local; el IAM obliga a cambiarlas según su política.

## Variables

```
SECURITY_ENABLED=true          # false abre todo (solo desarrollo; el arranque lo avisa en el log)
IAM_BASE_URL=http://localhost:4003/api/v1
IAM_PROJECT_CODE=EMISION_CMS
CMS_API_KEY=...                # vacío = sin llave de sistema
CMS_API_KEY_NAME=scripts
```

Los scripts de `scripts/` leen `CMS_API_KEY` (por defecto `dev-api-key`, el valor con el que se
arranca el servidor en desarrollo).

## Contraseña temporal y cambio obligatorio

Cuando el IAM crea una cuenta con contraseña temporal (marca `mustChangePassword`), la consola
lo detecta al entrar: `POST /api/auth/login` devuelve `userId` y `mustChangePassword`, y la
tarjeta de acceso muestra el formulario "Define tu contraseña" antes de dejar trabajar. El
cambio viaja por `POST /api/auth/change-password` (sesión requerida; `userId`,
`currentPassword`, `newPassword` de al menos 10 caracteres) al `/auth/change-password` del
IAM, que verifica la contraseña actual y borra la marca. El mismo formulario sirve de
autoservicio desde el botón "Contraseña" de la sesión. `scripts/verify_password_change.py`
(10 comprobaciones) lo prueba contra el IAM local. Para operar sin la consola del IAM hay
`scripts/iam_crear_usuario_cms.py` (alta con rol CMS_* y proyecto) y
`scripts/iam_cambiar_clave.py` (cambio de clave por consola).
