# -*- coding: utf-8 -*-
"""Crea (o actualiza) un usuario en el IAM corporativo con acceso al CMS: le asigna el proyecto
EMISION_CMS y un rol CMS_* ya registrado por iam_register_cms.py. Pensado para correrse en el
servidor de backend, donde el IAM escucha, cuando la consola del IAM no esta al alcance.

Variables: IAM_BASE_URL, IAM_ADMIN_USER, IAM_ADMIN_PASSWORD (administrador del IAM),
NEW_USERNAME, NEW_EMAIL, NEW_FIRST, NEW_LAST, NEW_ROLE (por defecto CMS_ADMIN) y NEW_PASSWORD
(si no viene, se genera una temporal y se imprime una sola vez)."""
import os, json, secrets, string, urllib.request, urllib.error

IAM = os.environ.get("IAM_BASE_URL", "http://127.0.0.1:4003/api/v1")
PROJECT = os.environ.get("IAM_PROJECT_CODE", "EMISION_CMS")
username = os.environ["NEW_USERNAME"].strip()
email = os.environ["NEW_EMAIL"].strip()
first = os.environ.get("NEW_FIRST", username).strip()
last = os.environ.get("NEW_LAST", "").strip() or "CMS"
role = os.environ.get("NEW_ROLE", "CMS_ADMIN").strip()
password = os.environ.get("NEW_PASSWORD") or ("Cms." + "".join(secrets.choice(string.ascii_letters + string.digits) for _ in range(10)) + "!")


def call(m, p, b=None, tok=None):
    r = urllib.request.Request(IAM + p, data=json.dumps(b).encode() if b is not None else None, method=m,
                               headers={"Content-Type": "application/json", **({"Authorization": "Bearer " + tok} if tok else {})})
    try:
        with urllib.request.urlopen(r, timeout=30) as x: return x.status, json.loads(x.read() or b"null")
    except urllib.error.HTTPError as e:
        try: return e.code, json.loads(e.read() or b"null")
        except Exception: return e.code, None


st, login = call("POST", "/auth/login", {"username": os.environ.get("IAM_ADMIN_USER", "admin"), "password": os.environ.get("IAM_ADMIN_PASSWORD", "")})
if st != 200: raise SystemExit("login del administrador del IAM fallo: %s %s" % (st, login))
tok = login["accessToken"]

st, roles = call("GET", "/roles", tok=tok)
rid = next((r["id"] for r in roles if r["name"] == role), None)
if rid is None: raise SystemExit("el rol %s no existe en el IAM; roles CMS disponibles: %s" % (role, sorted(r["name"] for r in roles if r["name"].startswith("CMS_"))))

st, users = call("GET", "/users", tok=tok)
u = next((x for x in users if x["username"] == username), None)
if u:
    projects = sorted(set(u.get("assignedProjects") or u.get("projectCodes") or []) | {PROJECT})
    rids = sorted({x["id"] if isinstance(x, dict) else x for x in (u.get("roles") or [])} | {rid})
    st, r = call("PUT", "/users/%d" % u["id"], {"email": email, "firstName": first, "lastName": last, "roleIds": rids, "projectCodes": projects}, tok=tok)
    print("usuario existente actualizado:", username, st, "(la contrasena no cambia)")
else:
    st, r = call("POST", "/users", {"username": username, "email": email, "password": password, "firstName": first, "lastName": last, "roleIds": [rid], "projectCodes": [PROJECT]}, tok=tok)
    if st not in (200, 201): raise SystemExit("no se pudo crear el usuario: %s %s" % (st, r))
    print("usuario creado:", username, "rol", role, "proyecto", PROJECT)
    print("contrasena temporal (cambiala al entrar):", password)

st, l = call("POST", "/auth/login", {"username": username, "password": password}) if not u else (0, None)
if st == 200:
    st, i = call("POST", "/auth/introspect", {"token": l["accessToken"], "projectCode": PROJECT})
    print("comprobacion: acceso=%s permisos=%s" % (i.get("projectAccessGranted"), sorted(p for p in i.get("permissions", []) if p.startswith("CMS:"))))
