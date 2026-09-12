# -*- coding: utf-8 -*-
"""Prueba local del cambio obligatorio de contrasena: crea un usuario con clave temporal en el IAM
local (must_change_password), entra por el CMS, cambia la clave desde el CMS y vuelve a entrar."""
import json, time, urllib.request, urllib.error
IAM = "http://localhost:4003/api/v1"; CMS = "http://localhost:8085/api"
def call(base, m, p, b=None, tok=None):
    r = urllib.request.Request(base + p, data=json.dumps(b).encode() if b is not None else None, method=m,
                               headers={"Content-Type": "application/json", **({"Authorization": "Bearer " + tok} if tok else {})})
    try:
        with urllib.request.urlopen(r, timeout=30) as x: return x.status, json.loads(x.read() or b"null")
    except urllib.error.HTTPError as e:
        try: return e.code, json.loads(e.read() or b"null")
        except Exception: return e.code, None
ok = bad = 0
def check(l, c, d=""):
    global ok, bad
    if c: ok += 1; print("  OK  ", l)
    else: bad += 1; print("  FAIL", l, str(d)[:300])
st, a = call(IAM, "POST", "/auth/login", {"username": "admin", "password": "Admin123!"})
check("login admin en el IAM local", st == 200, (st, a)); tok = a["accessToken"]
st, roles = call(IAM, "GET", "/roles", tok=tok); rid = next(r["id"] for r in roles if r["name"] == "CMS_MESA_CONTROL")
st, users = call(IAM, "GET", "/users", tok=tok)
u = next((x for x in users if x["username"] == "prueba.pwd"), None)
if u: call(IAM, "DELETE", "/users/%d" % u["id"], tok=tok)
st, u = call(IAM, "POST", "/users", {"username": "prueba.pwd", "email": "prueba.pwd@finsus.mx", "password": "Temporal.12345", "firstName": "Prueba", "lastName": "Clave", "roleIds": [rid], "projectCodes": ["EMISION_CMS"]}, tok=tok)
check("usuario con clave temporal creado en el IAM", st in (200, 201), (st, u))
st, d = call(IAM, "GET", "/users/%d" % u["id"], tok=tok)
check("el IAM lo marca con cambio obligatorio", d.get("mustChangePassword") is True, d)
st, l = call(CMS, "POST", "/auth/login", {"username": "prueba.pwd", "password": "Temporal.12345"})
check("el CMS devuelve mustChangePassword=true y userId al entrar", st == 200 and l.get("mustChangePassword") is True and l.get("userId") == u["id"], (st, l))
st, r = call(CMS, "POST", "/auth/change-password", {"userId": u["id"], "currentPassword": "incorrecta", "newPassword": "Nueva.Clave.2026"}, tok=l["accessToken"])
check("clave actual incorrecta se rechaza (400)", st == 400, (st, r))
st, r = call(CMS, "POST", "/auth/change-password", {"userId": u["id"], "currentPassword": "Temporal.12345", "newPassword": "corta"}, tok=l["accessToken"])
check("clave nueva corta se rechaza (400)", st == 400, (st, r))
st, r = call(CMS, "POST", "/auth/change-password", {"userId": u["id"], "currentPassword": "Temporal.12345", "newPassword": "Nueva.Clave.2026"}, tok=l["accessToken"])
check("cambio de clave desde el CMS", st == 200 and (r or {}).get("ok"), (st, r))
st, l2 = call(CMS, "POST", "/auth/login", {"username": "prueba.pwd", "password": "Nueva.Clave.2026"})
check("entra con la nueva y ya no pide cambio", st == 200 and l2.get("mustChangePassword") is False, (st, l2))
st, l3 = call(CMS, "POST", "/auth/login", {"username": "prueba.pwd", "password": "Temporal.12345"})
check("la temporal ya no sirve (401)", st == 401, st)
st, r = call(CMS, "POST", "/auth/change-password", {"userId": u["id"], "currentPassword": "Nueva.Clave.2026", "newPassword": "Otra.Clave.2026"})
check("sin sesion no se puede cambiar (401)", st == 401, (st, r))
call(IAM, "DELETE", "/users/%d" % u["id"], tok=tok)
print("RESULTADO: %d OK, %d FAIL" % (ok, bad)); raise SystemExit(1 if bad else 0)
