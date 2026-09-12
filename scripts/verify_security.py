# -*- coding: utf-8 -*-
"""Console and API security through the corporate IAM: no session -> 401, bad token -> 401,
each demo role sees what it may and is refused what it may not, the system API key opens
everything, and the audit trail names the authenticated person, not what the request claimed."""
import os, json, subprocess, time, urllib.request, urllib.error
import sys as _sys; _sys.path.insert(0, __import__("os").path.dirname(__file__)); from kyc_demo import identity

CMS = "http://localhost:8085/api"
PSQL = r"C:\Program Files\PostgreSQL\17\bin\psql.exe"
API_KEY = os.environ.get("CMS_API_KEY", "dev-api-key")
RUN = str(int(time.time()) % 100000)


def http(m, p, b=None, token=None, api_key=None):
    h = {"Content-Type": "application/json", "Accept": "application/json"}
    if token: h["Authorization"] = "Bearer " + token
    if api_key: h["X-Api-Key"] = api_key
    r = urllib.request.Request(CMS + p, data=json.dumps(b).encode() if b is not None else None, method=m, headers=h)
    try:
        with urllib.request.urlopen(r, timeout=60) as x: return x.status, json.loads(x.read() or b"null")
    except urllib.error.HTTPError as e:
        try: return e.code, json.loads(e.read() or b"null")
        except Exception: return e.code, None


def sql(q):
    return subprocess.run([PSQL, "-h", "localhost", "-U", "postgres", "-d", "cms_mexico", "-tAc", q],
                          capture_output=True, text=True, env=dict(os.environ, PGPASSWORD="alodiga.123")).stdout.strip()


ok = bad = 0


def check(label, cond, detail=""):
    global ok, bad
    if cond:
        ok += 1; print(f"  OK   {label}")
    else:
        bad += 1; print(f"  FAIL {label}  {str(detail)[:320]}")


def login(u, p):
    st, r = http("POST", "/auth/login", {"username": u, "password": p})
    return st, r


print("== 1. sin sesion ==")
st, r = http("GET", "/cards?page=0&size=1"); check("GET /cards sin token -> 401 AUTH_REQUIRED", st == 401 and r.get("errorCode") == "AUTH_REQUIRED", (st, r))
st, r = http("POST", "/authorization", {"cardId": 1, "amount": 1}); check("POST /authorization sin token -> 401", st == 401, st)
st, r = http("GET", "/cards?page=0&size=1", token="no.es.un.token"); check("token invalido -> 401 AUTH_TOKEN_INVALID", st == 401 and r.get("errorCode") == "AUTH_TOKEN_INVALID", (st, r))
st, r = http("GET", "/cards?page=0&size=1", api_key="llave-equivocada"); check("llave de API equivocada -> 401", st == 401 and r.get("errorCode") == "AUTH_BAD_API_KEY", (st, r))
st, r = login("mesa.cms", "incorrecta"); check("login con contrasena incorrecta -> 401", st == 401 and r.get("errorCode") == "AUTH_INVALID_CREDENTIALS", (st, r))
# a throwaway IAM user with no access to EMISION_CMS (created and removed through the IAM API)
IAM = os.environ.get("IAM_BASE_URL", "http://localhost:4003/api/v1")
def iam(m, p, b=None, tok=None):
    r = urllib.request.Request(IAM + p, data=json.dumps(b).encode() if b is not None else None, method=m, headers={"Content-Type": "application/json", **({"Authorization": "Bearer " + tok} if tok else {})})
    try:
        with urllib.request.urlopen(r, timeout=30) as x: return x.status, json.loads(x.read() or b"null")
    except urllib.error.HTTPError as e:
        try: return e.code, json.loads(e.read() or b"null")
        except Exception: return e.code, None
st, iam_admin = iam("POST", "/auth/login", {"username": "admin", "password": "Admin123!"}); IAM_TOK = iam_admin["accessToken"]
st, roles = iam("GET", "/roles", tok=IAM_TOK); auditor_role = next(r["id"] for r in roles if r["name"] == "AUDITOR")
st, tmp = iam("POST", "/users", {"username": "sinproyecto" + RUN, "email": "sinproyecto%s@finsus.mx" % RUN, "password": "Fuera.2026!", "firstName": "Sin", "lastName": "Proyecto", "roleIds": [auditor_role], "projectCodes": ["KYC_KYB"]}, tok=IAM_TOK)
st, r = login("sinproyecto" + RUN, "Fuera.2026!"); check("usuario del IAM sin acceso al proyecto EMISION_CMS -> 403 AUTH_NO_PROJECT_ACCESS", st == 403 and r.get("errorCode") == "AUTH_NO_PROJECT_ACCESS", (st, r))
if isinstance(tmp, dict) and tmp.get("id"): iam("DELETE", "/users/%d" % tmp["id"], tok=IAM_TOK)
st, r = http("GET", "/reports"); check("las rutas publicas siguen abiertas: /api/auth/**, consola y Swagger", http("GET", "/auth/me")[0] == 401 and urllib.request.urlopen("http://localhost:8085/swagger-ui/index.html").status == 200, "")

print("== 2. mesa de control: opera, no toca fraude ni reportes sellados ==")
st, mesa = login("mesa.cms", "Mesa.2026!")
check("login: token, roles y permisos del proyecto", st == 200 and mesa["accessToken"] and "CMS_MESA_CONTROL" in mesa["roles"] and set(mesa["permissions"]) == {"CMS:READ", "CMS:OPERATE", "CMS:DISPUTES"}, (st, mesa if st != 200 else mesa.get("permissions")))
T = mesa["accessToken"]
st, me = http("GET", "/auth/me", token=T); check("/auth/me devuelve la identidad", st == 200 and me["username"] == "mesa.cms" and me["project"] == "EMISION_CMS", (st, me))
st, cards = http("GET", "/cards?page=0&size=3", token=T); check("puede consultar tarjetas", st == 200 and cards["content"], st)
ppre = int(sql("select id from card_products where product_code='PRE-MX'"))
st, c = http("POST", "/customers", {**identity("Titular Seguridad " + RUN), "fullName": "Titular Seguridad " + RUN, "phoneNumber": "5550000050", "cardLast4": "6" + RUN[-3:], "initialDeposit": 10}, token=T)
st, k = http("POST", "/cards/issue", {"customerId": c["id"], "productId": ppre, "embossedName": "TITULAR SEG", "cardCategory": "VIRTUAL", "last4": "6" + RUN[-3:], "initialDeposit": 100}, token=T)
check("puede emitir una tarjeta (CMS:OPERATE)", st in (200, 201) and k.get("id"), (st, k))
CARD = k["id"]
st, r = http("POST", f"/cards/{CARD}/status", {"status": "BLOCKED", "by": "alguien.inventado"}, token=T)
check("puede bloquearla", st == 200, (st, r))
au = sql("select username from audit_logs where action='CARD_STATUS_BLOCKED' and entity_id='%d'" % CARD)
check("la auditoria registra a la persona autenticada, no el 'by' del cuerpo", au == "mesa.cms", au)
st, r = http("POST", "/fraud/blocklist", {"type": "MERCHANT_ID", "value": "M-SEG-" + RUN, "reason": "x"}, token=T)
check("no puede tocar fraude: 403 AUTH_FORBIDDEN", st == 403 and r.get("errorCode") == "AUTH_FORBIDDEN", (st, r))
st, r = http("GET", "/fraud/alerts?page=0&size=1", token=T); check("pero si consultar alertas (CMS:READ)", st == 200, st)
st, r = http("POST", "/reports/DAILY_ISSUANCE/runs", {"by": "mesa"}, token=T); check("no puede sellar reportes: 403", st == 403, st)
st, r = http("GET", "/reports/DAILY_ISSUANCE", token=T); check("pero si verlos", st == 200, st)
st, r = http("POST", "/products", {"productCode": "SEG-" + RUN, "productName": "x", "cardType": "PREPAID", "paymentType": "PREPAID", "network": "VISA", "bin": "453200"}, token=T)
check("no puede crear productos: 403", st == 403, st)

print("== 3. analista de fraude ==")
st, fr = login("fraude.cms", "Fraude.2026!"); F = fr["accessToken"]
st, r = http("POST", "/fraud/blocklist", {"type": "MERCHANT_ID", "value": "M-SEG-" + RUN, "reason": "prueba de permisos", "by": "otro"}, token=F)
check("puede bloquear un comercio (CMS:FRAUD)", st in (200, 201), (st, r))
BL = r.get("id") if isinstance(r, dict) else None
au = sql("select added_by from fraud_blocklist where value='M-SEG-%s'" % RUN)
st, r = http("POST", "/guild/verify/merchant/M-SEG-" + RUN, token=F); check("puede consultar al gremio", st == 200, st)
st, r = http("POST", "/cards/issue", {"customerId": c["id"], "productId": ppre, "embossedName": "X", "cardCategory": "VIRTUAL", "last4": "9999", "initialDeposit": 1}, token=F)
check("no puede emitir tarjetas: 403", st == 403, st)
st, r = http("POST", "/disputes", {"cardId": CARD, "approvalCode": "X", "reasonCode": "13.1"}, token=F); check("no puede abrir aclaraciones: 403", st == 403, st)
if BL: http("DELETE", f"/fraud/blocklist/{BL}", token=F)

print("== 4. auditor: solo lectura y reportes sellados ==")
st, ad = login("auditor.cms", "Auditor.2026!"); A = ad["accessToken"]
st, r = http("GET", "/audit", token=A); check("lee la auditoria", st == 200, st)
st, r = http("GET", "/cards?page=0&size=2", token=A); check("lee tarjetas", st == 200, st)
st, r = http("POST", "/reports/INACTIVE_CARDS/runs", {"by": "x"}, token=A); check("sella un reporte (CMS:REPORTS) y queda a su nombre", st == 201 and r["generatedBy"] == "auditor.cms", (st, r))
st, r = http("POST", f"/cards/{CARD}/status", {"status": "ACTIVE"}, token=A); check("no puede cambiar estados: 403", st == 403, st)
st, r = http("POST", "/plastics", {"cardId": CARD, "reason": "NEW"}, token=A); check("no puede pedir plasticos: 403", st == 403, st)

print("== 5. administrador del CMS y llave de sistema ==")
st, adm = login("admin.cms", "AdminCms.2026!"); M = adm["accessToken"]
st, r = http("PUT", f"/products/{ppre}", {"dailyLimit": 1000}, token=M); check("admin.cms edita productos (CMS:ADMIN)", st == 200, (st, r))
st, r = http("POST", "/fraud/blocklist", {"type": "COUNTRY", "value": "ZZ", "reason": "prueba admin"}, token=M); check("y toca fraude", st in (200, 201), st)
if isinstance(r, dict) and r.get("id"): http("DELETE", f"/fraud/blocklist/{r['id']}", token=M)
st, r = http("GET", "/guild/status", api_key=API_KEY); check("la llave de sistema consulta", st == 200, (st, r))
st, r = http("POST", f"/cards/{CARD}/status", {"status": "ACTIVE", "by": "verify_security"}, api_key=API_KEY)
check("la llave de sistema opera y la auditoria guarda el operador declarado", st == 200 and sql("select username from audit_logs where action='CARD_STATUS_ACTIVE' and entity_id='%d' order by id desc limit 1" % CARD) == "verify_security", st)
st, r = http("POST", "/auth/logout", token=T); check("logout de la mesa", st == 200, st)
au = sql("select count(*) from audit_logs where action in ('LOGIN','LOGOUT') and username='mesa.cms'")
check("login y logout auditados", int(au) >= 2, au)
st, adm2 = login("admin", "Admin123!"); check("el SUPER_ADMIN del IAM entra con todos los permisos", st == 200 and set(adm2["permissions"]) == {"CMS:READ", "CMS:OPERATE", "CMS:FRAUD", "CMS:DISPUTES", "CMS:REPORTS", "CMS:ADMIN"}, (st, adm2.get("permissions") if isinstance(adm2, dict) else adm2))

print("== 6. limpieza ==")
out = subprocess.run([PSQL, "-h", "localhost", "-U", "postgres", "-d", "cms_mexico", "-c", """
with c as (select %d as id),
 d1 as (delete from ledger_entries where ledger_account_id in (select id from ledger_accounts where card_id in (select id from c))),
 d2 as (delete from ledger_accounts where card_id in (select id from c)),
 d3 as (delete from card_controls where card_id in (select id from c)),
 d4 as (delete from authorization_attempts where card_id in (select id from c)),
 d5 as (delete from fraud_alerts where card_id in (select id from c)),
 d6 as (delete from step_up_challenges where card_id in (select id from c)),
 d7 as (delete from plastics where card_id in (select id from c)),
 d8 as (delete from disputes where card_id in (select id from c)),
 d9 as (delete from authorization_holds where card_id in (select id from c)),
 d10 as (delete from guild_alerts where card_id in (select id from c))
delete from cards where id in (select id from c)""" % CARD], capture_output=True, text=True, env=dict(os.environ, PGPASSWORD="alodiga.123"))
sql("delete from customers where id=%d and not exists (select 1 from cards where customer_id=%d)" % (c["id"], c["id"]))
check("datos de prueba borrados", sql("select count(*) from cards where id=%d" % CARD) == "0", out.stderr.strip()[:200])

print(f"\nRESULTADO: {ok} OK, {bad} FAIL")
raise SystemExit(1 if bad else 0)
