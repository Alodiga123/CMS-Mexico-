# -*- coding: utf-8 -*-
"""Cambia la contrasena de un usuario del IAM corporativo (POST /auth/change-password) sin pasar por
su consola: pide la clave actual y la nueva por teclado, localiza el id del usuario con el
administrador del IAM y comprueba el nuevo acceso. Variables: IAM_BASE_URL, IAM_ADMIN_USER,
IAM_ADMIN_PASSWORD, TARGET_USERNAME."""
import os, json, getpass, urllib.request, urllib.error

IAM = os.environ.get("IAM_BASE_URL", "http://127.0.0.1:4003/api/v1")
username = os.environ["TARGET_USERNAME"].strip()


def call(m, p, b=None, tok=None):
    r = urllib.request.Request(IAM + p, data=json.dumps(b).encode() if b is not None else None, method=m,
                               headers={"Content-Type": "application/json", **({"Authorization": "Bearer " + tok} if tok else {})})
    try:
        with urllib.request.urlopen(r, timeout=30) as x: return x.status, json.loads(x.read() or b"null")
    except urllib.error.HTTPError as e:
        try: return e.code, json.loads(e.read() or b"null")
        except Exception: return e.code, None


st, login = call("POST", "/auth/login", {"username": os.environ.get("IAM_ADMIN_USER", "admin"), "password": os.environ.get("IAM_ADMIN_PASSWORD", "")})
if st != 200: raise SystemExit("login del administrador del IAM fallo: %s" % st)
st, users = call("GET", "/users", tok=login["accessToken"])
u = next((x for x in users if x["username"] == username), None)
if not u: raise SystemExit("el usuario %s no existe en el IAM" % username)

actual = getpass.getpass("Contrasena actual de %s: " % username)
nueva = getpass.getpass("Contrasena nueva: ")
if nueva != getpass.getpass("Repite la nueva: "): raise SystemExit("las contrasenas nuevas no coinciden")
if len(nueva) < 10: raise SystemExit("usa al menos 10 caracteres")

st, r = call("POST", "/auth/change-password", {"userId": str(u["id"]), "currentPassword": actual, "newPassword": nueva})
if st != 200: raise SystemExit("el IAM rechazo el cambio: %s %s" % (st, r))
print("contrasena cambiada:", (r or {}).get("message", "ok"))
st, l = call("POST", "/auth/login", {"username": username, "password": nueva})
print("comprobacion de acceso con la nueva contrasena:", "ok" if st == 200 else "fallo %s" % st)
