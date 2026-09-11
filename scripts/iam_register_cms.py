# -*- coding: utf-8 -*-
"""Registers the CMS in the corporate IAM: project EMISION_CMS, its six permissions, four roles
and demo users; grants the project to the IAM administrator. Idempotent: re-running updates
nothing that already exists. Needs IAM_ADMIN_USER / IAM_ADMIN_PASSWORD (default admin / Admin123!)
and, for the permissions (the IAM has no API for them), the IAM database via psql."""
import os, json, subprocess, urllib.request, urllib.error

IAM = os.environ.get("IAM_BASE_URL", "http://localhost:4003/api/v1")
PSQL = os.environ.get("PSQL", r"C:\Program Files\PostgreSQL\17\bin\psql.exe")
IAM_DB = os.environ.get("IAM_DB", "finsus_iam")
PGPASSWORD = os.environ.get("PGPASSWORD", "alodiga.123")
PROJECT = "EMISION_CMS"

PERMISSIONS = [
    ("CMS:READ", "Consulta del CMS", "Ver tarjetas, clientes, autorizaciones, plasticos, aclaraciones, alertas y reportes"),
    ("CMS:OPERATE", "Operacion de tarjetas", "Emitir, bloquear, controles, plasticos, retenciones, ledger y conciliacion"),
    ("CMS:FRAUD", "Fraude en linea", "Alertas, listas de bloqueo, verificacion reforzada y antifraude de industria"),
    ("CMS:DISPUTES", "Aclaraciones", "Abrir y gestionar aclaraciones y contracargos"),
    ("CMS:REPORTS", "Reportes sellados", "Correr y sellar reportes operativos y regulatorios"),
    ("CMS:ADMIN", "Administracion del CMS", "Productos, promociones, llaves HSM y parametros"),
]
ROLES = [
    ("CMS_MESA_CONTROL", "Mesa de control del CMS: opera tarjetas, plasticos y aclaraciones", ["CMS:READ", "CMS:OPERATE", "CMS:DISPUTES"]),
    ("CMS_ANALISTA_FRAUDE", "Analista de fraude del CMS: alertas, listas y gremio", ["CMS:READ", "CMS:FRAUD"]),
    ("CMS_AUDITOR", "Auditoria del CMS: solo lectura y reportes sellados", ["CMS:READ", "CMS:REPORTS"]),
    ("CMS_ADMIN", "Administrador del CMS: todo", ["CMS:READ", "CMS:OPERATE", "CMS:FRAUD", "CMS:DISPUTES", "CMS:REPORTS", "CMS:ADMIN"]),
]
USERS = [
    ("mesa.cms", "mesa.cms@finsus.mx", "Mesa", "Control", "Mesa.2026!", ["CMS_MESA_CONTROL"]),
    ("fraude.cms", "fraude.cms@finsus.mx", "Ana", "Torres", "Fraude.2026!", ["CMS_ANALISTA_FRAUDE"]),
    ("auditor.cms", "auditor.cms@finsus.mx", "Auditoria", "Interna", "Auditor.2026!", ["CMS_AUDITOR"]),
    ("admin.cms", "admin.cms@finsus.mx", "Admin", "CMS", "AdminCms.2026!", ["CMS_ADMIN"]),
]


def call(m, p, b=None, tok=None):
    r = urllib.request.Request(IAM + p, data=json.dumps(b).encode() if b is not None else None, method=m,
                               headers={"Content-Type": "application/json", **({"Authorization": "Bearer " + tok} if tok else {})})
    try:
        with urllib.request.urlopen(r, timeout=30) as x: return x.status, json.loads(x.read() or b"null")
    except urllib.error.HTTPError as e:
        try: return e.code, json.loads(e.read() or b"null")
        except Exception: return e.code, None


def sql(q):
    r = subprocess.run([PSQL, "-h", "localhost", "-U", "postgres", "-d", IAM_DB, "-tAc", q], capture_output=True, text=True,
                       env=dict(os.environ, PGPASSWORD=PGPASSWORD))
    if r.returncode != 0: raise SystemExit("psql: " + r.stderr.strip())
    return r.stdout.strip()


st, login = call("POST", "/auth/login", {"username": os.environ.get("IAM_ADMIN_USER", "admin"), "password": os.environ.get("IAM_ADMIN_PASSWORD", "Admin123!")})
if st != 200: raise SystemExit("IAM login failed: %s %s" % (st, login))
tok = login["accessToken"]
print("IAM:", IAM, "como", login["username"])

# 1. project
st, projects = call("GET", "/projects", tok=tok)
if not any(p["code"] == PROJECT for p in projects):
    st, p = call("POST", "/projects", {"code": PROJECT, "name": "CMS de Emision y Autorizador", "description": "Emision de tarjetas (prepago, debito con saldo del core, credito), autorizador, fraude, aclaraciones, plasticos y reportes", "apiKey": None}, tok=tok)
    print("proyecto creado:", st, p.get("code") if isinstance(p, dict) else p)
else:
    print("proyecto ya existe:", PROJECT)

# 2. permissions (SQL: the IAM has no endpoint to create them)
for code, name, desc in PERMISSIONS:
    sql("insert into tb_permissions (code, name, description, project_code, created_at) select '%s', '%s', '%s', '%s', now() where not exists (select 1 from tb_permissions where code='%s')" % (code, name, desc, PROJECT, code))
perm_ids = {row.split("|")[0]: int(row.split("|")[1]) for row in sql("select code||'|'||id from tb_permissions where project_code='%s'" % PROJECT).splitlines()}
print("permisos:", perm_ids)

# 3. roles
st, roles = call("GET", "/roles", tok=tok)
by_name = {r["name"]: r for r in roles}
role_ids = {}
for name, desc, perms in ROLES:
    ids = sorted(perm_ids[p] for p in perms)
    if name in by_name:
        st, r = call("PUT", "/roles/%d" % by_name[name]["id"], {"name": name, "description": desc, "permissionIds": ids}, tok=tok)
        print("rol actualizado:", name, st)
        role_ids[name] = by_name[name]["id"]
    else:
        st, r = call("POST", "/roles", {"name": name, "description": desc, "permissionIds": ids}, tok=tok)
        print("rol creado:", name, st, r.get("id") if isinstance(r, dict) else r)
        role_ids[name] = r["id"]

# 4. users
st, users = call("GET", "/users", tok=tok)
existing = {u["username"]: u for u in users}
if os.environ.get("IAM_SKIP_DEMO_USERS") == "1":
    USERS = []  # IAM corporativo: solo proyecto, permisos y roles; los usuarios reales se asignan desde el IAM
for username, email, first, last, password, rnames in USERS:
    rids = sorted(role_ids[r] for r in rnames)
    if username in existing:
        u = existing[username]
        projects = sorted(set(u.get("assignedProjects") or u.get("projectCodes") or []) | {PROJECT})
        st, r = call("PUT", "/users/%d" % u["id"], {"email": email, "firstName": first, "lastName": last, "roleIds": rids, "projectCodes": projects}, tok=tok)
        print("usuario actualizado:", username, st)
    else:
        st, r = call("POST", "/users", {"username": username, "email": email, "password": password, "firstName": first, "lastName": last, "roleIds": rids, "projectCodes": [PROJECT]}, tok=tok)
        print("usuario creado:", username, st, (r.get("id") if isinstance(r, dict) else r))

# 5. the IAM administrator also gets the project (SUPER_ADMIN passes anyway, but keep the data honest)
admin = existing.get(login["username"]) or next((u for u in users if u["username"] == login["username"]), None)
if admin:
    projects = sorted(set(admin.get("assignedProjects") or admin.get("projectCodes") or []) | {PROJECT})
    st, r = call("PUT", "/users/%d" % admin["id"], {"email": admin.get("email"), "firstName": admin.get("firstName"), "lastName": admin.get("lastName"),
                                                     "roleIds": [x["id"] if isinstance(x, dict) else x for x in (admin.get("roles") or [])] or None, "projectCodes": projects}, tok=tok)
    print("admin con proyecto:", st)

# 6. proof: each demo user introspects with the right permissions
for username, _, _, _, password, _ in USERS:
    st, l = call("POST", "/auth/login", {"username": username, "password": password})
    if st != 200: print("  login", username, "->", st, l); continue
    st, i = call("POST", "/auth/introspect", {"token": l["accessToken"], "projectCode": PROJECT})
    print("  %-12s acceso=%s permisos=%s" % (username, i.get("projectAccessGranted"), sorted(p for p in i.get("permissions", []) if p.startswith("CMS:"))))
