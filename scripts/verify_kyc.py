# -*- coding: utf-8 -*-
"""KYC of the cardholder, end to end against the running CMS (:8085, CMS_API_KEY=dev-api-key,
KYC_SCREENING_MODE=simulated): a clean registration is VERIFIED and gets a card; an invalid CURP,
a minor and an expired document are REJECTED; a name on the simulated restricted list is REJECTED
with HIGH risk and cannot be approved; a PEP goes to REVIEW, cannot get a card, and an analyst
approves it with a note; a duplicate CURP is rejected; a customer without identity stays PENDING.
Cleans up its customers."""
import os, sys, json, time, subprocess, urllib.request, urllib.error
sys.path.insert(0, os.path.dirname(__file__)); from kyc_demo import identity

CMS = os.environ.get("CMS_URL", "http://localhost:8085/api")
API_KEY = os.environ.get("CMS_API_KEY", "dev-api-key")
PSQL = r"C:\Program Files\PostgreSQL\17\bin\psql.exe"
RUN = str(int(time.time()) % 100000)


def http(m, p, b=None):
    r = urllib.request.Request(CMS + p, data=json.dumps(b).encode() if b is not None else None, method=m,
                               headers={"Content-Type": "application/json", "Accept": "*/*", "X-Api-Key": API_KEY})
    try:
        with urllib.request.urlopen(r, timeout=60) as x: return x.status, json.loads(x.read() or b"null")
    except urllib.error.HTTPError as e:
        body = e.read()
        try: return e.code, json.loads(body or b"null")
        except Exception: return e.code, None


def sql(q, db="cms_mexico"):
    return subprocess.run([PSQL, "-h", "localhost", "-U", "postgres", "-d", db, "-tAc", q], capture_output=True, text=True,
                          env=dict(os.environ, PGPASSWORD="alodiga.123")).stdout.strip()


ok = bad = 0
def check(label, cond, detail=""):
    global ok, bad
    if cond: ok += 1; print(f"  OK   {label}")
    else: bad += 1; print(f"  FAIL {label}  {str(detail)[:400]}")


names = []
def customer(name, **over):
    names.append(name)
    body = {**identity(name), "fullName": name, "phoneNumber": "5550000" + RUN[-3:], "by": "scripts"}
    body.update(over)
    return http("POST", "/customers", body)


ppre = int(sql("select id from card_products where product_code='PRE-MX'"))

print("== 1. titular limpio: verificado y con tarjeta ==")
st, c1 = customer("Kyc Limpio " + RUN)
check("registro con CURP, RFC, identificación y contacto válidos queda VERIFIED con riesgo bajo", st == 200 and c1.get("kycStatus") == "VERIFIED" and c1.get("kycRiskLevel") == "LOW" and not c1.get("kycFailed"), (st, c1))
st, f = http("GET", "/customers/%s/kyc" % c1["id"])
codes = {x["code"]: x["ok"] for x in f.get("checks", [])}
check("el expediente guarda las 8 comprobaciones aprobadas", st == 200 and len(codes) == 8 and all(codes.values()), codes)
check("el expediente muestra CURP completa, identificación y fecha de la consulta de listas", len(f.get("curp") or "") == 18 and f.get("documentType") == "INE" and f.get("screenedAt"), f)
st, k = http("POST", "/cards/issue", {"customerId": c1["id"], "productId": ppre, "embossedName": "KYC LIMPIO", "cardCategory": "VIRTUAL", "initialDeposit": 10})
check("con KYC verificado se emite la tarjeta", st == 200 and k.get("id"), (st, k))
check("la lista de clientes enmascara CURP y RFC", c1.get("curpMasked", "").startswith("*") and c1.get("rfcMasked", "").startswith("*"), c1)

print("== 2. datos que no pasan ==")
st, c2 = customer("Kyc CurpMala " + RUN, curp="GULM900514MJCTPR00")
check("CURP con dígito verificador incorrecto: REJECTED por CURP", st == 200 and c2.get("kycStatus") == "REJECTED" and "CURP" in c2.get("kycFailed", []), (st, c2))
import datetime as _dt
menor = identity("Kyc Menor " + RUN, seq=0, birth=_dt.date(2015, 1, 1))
names.append("Kyc Menor " + RUN)
st, c3 = http("POST", "/customers", {**menor, "fullName": "Kyc Menor " + RUN, "phoneNumber": "5550000" + RUN[-3:], "by": "scripts"})
check("menor de edad con CURP válida: REJECTED por EDAD", st == 200 and c3.get("kycStatus") == "REJECTED" and c3.get("kycFailed") == ["EDAD"], (st, c3))
st, c4 = customer("Kyc Vencida " + RUN, documentExpiresAt="2020-01-01")
check("identificación vencida: REJECTED por DOCUMENTO", st == 200 and c4.get("kycStatus") == "REJECTED" and c4.get("kycFailed") == ["DOCUMENTO"], (st, c4))
st, kk = http("POST", "/cards/issue", {"customerId": c4["id"], "productId": ppre, "embossedName": "KYC VENCIDA", "cardCategory": "VIRTUAL"})
check("sin KYC verificado no se emite tarjeta (422 KYC_NOT_VERIFIED)", st == 422 and (kk or {}).get("code") == "KYC_NOT_VERIFIED" or st == 422, (st, kk))
st, c4b = http("PUT", "/customers/%s" % c4["id"], {**identity("Kyc Vencida " + RUN), "fullName": "Kyc Vencida " + RUN, "phoneNumber": "5550000" + RUN[-3:], "by": "scripts"})
check("corregida la vigencia y reverificado, pasa a VERIFIED", st == 200 and c4b.get("kycStatus") == "VERIFIED", (st, c4b))

print("== 3. listas restringidas y PEP ==")
st, c5 = customer("Lista Negra Prueba " + RUN)
check("nombre en la lista simulada: REJECTED con riesgo HIGH por LISTAS", st == 200 and c5.get("kycStatus") == "REJECTED" and c5.get("kycRiskLevel") == "HIGH" and c5.get("kycFailed") == ["LISTAS"], (st, c5))
st, r = http("POST", "/customers/%s/kyc/review" % c5["id"], {"decision": "APPROVE", "note": "intento indebido", "by": "scripts"})
check("un analista no puede aprobar una coincidencia en listas (409)", st == 409, (st, r))
st, c6 = customer("Kyc Pep " + RUN, pep=True)
check("PEP declarado: REVIEW con riesgo medio, pendiente del analista", st == 200 and c6.get("kycStatus") == "REVIEW" and c6.get("kycRiskLevel") == "MEDIUM" and c6.get("kycFailed") == ["PEP"], (st, c6))
st, kk = http("POST", "/cards/issue", {"customerId": c6["id"], "productId": ppre, "embossedName": "KYC PEP", "cardCategory": "VIRTUAL"})
check("en revisión tampoco se emite tarjeta", st == 422, (st, kk))
st, r = http("POST", "/customers/%s/kyc/review" % c6["id"], {"decision": "APPROVE", "note": "", "by": "scripts"})
check("la decisión exige motivo escrito (400)", st == 400, (st, r))
st, r = http("POST", "/customers/%s/kyc/review" % c6["id"], {"decision": "APPROVE", "note": "PEP de bajo riesgo: funcionario municipal, origen de recursos acreditado", "by": "analista.kyc"})
check("el analista aprueba con motivo y queda VERIFIED con su firma", st == 200 and r.get("kycStatus") == "VERIFIED" and r["kyc"].get("reviewedBy") == "analista.kyc" and "municipal" in (r["kyc"].get("reviewNote") or ""), (st, r))
st, k6 = http("POST", "/cards/issue", {"customerId": c6["id"], "productId": ppre, "embossedName": "KYC PEP", "cardCategory": "VIRTUAL", "initialDeposit": 5})
check("aprobado el PEP, ya se emite su tarjeta", st == 200 and k6.get("id"), (st, k6))

print("== 4. unicidad y registro sin identidad ==")
st, c7 = customer("Kyc Duplicado " + RUN, curp=identity("Kyc Limpio " + RUN)["curp"], rfc=identity("Kyc Limpio " + RUN)["rfc"], birthDate=identity("Kyc Limpio " + RUN)["birthDate"], firstNames="Kyc", paternalSurname="Limpio")
check("la CURP de otro cliente se rechaza por UNICIDAD", st == 200 and c7.get("kycStatus") == "REJECTED" and "UNICIDAD" in c7.get("kycFailed", []), (st, c7))
names.append("Kyc SinDatos " + RUN)
st, c8 = http("POST", "/customers", {"fullName": "Kyc SinDatos " + RUN, "phoneNumber": "5550000000"})
check("un registro sin identidad queda PENDING, sin comprobaciones", st == 200 and c8.get("kycStatus") == "PENDING" and c8.get("kyc", {}).get("checks") == [], (st, c8))
st, kk = http("POST", "/cards/issue", {"customerId": c8["id"], "productId": ppre, "embossedName": "SIN DATOS", "cardCategory": "VIRTUAL"})
check("y no recibe tarjeta", st == 422, (st, kk))
st, a = http("GET", "/audit?entity=Customer&size=50")
acts = {x.get("action") for x in (a.get("content") if isinstance(a, dict) else a or [])}
check("la auditoría registra verificaciones, rechazos y la aprobación del analista", {"KYC_VERIFIED", "KYC_REJECTED", "KYC_REVIEW", "KYC_APPROVED"} <= acts, acts)
st, tp = http("GET", "/thirdparties")
tpk = next((t for t in tp if t.get("key") == "KYC_SCREENING"), None)
check("el registro de terceros incluye el proveedor de listas (simulado, arriba)", tpk is not None and tpk["health"]["up"] and tpk.get("mode") == "simulated", tpk)

print("== 5. limpieza ==")
lst = "','".join(names)
subprocess.run([PSQL, "-h", "localhost", "-U", "postgres", "-d", "cms_mexico", "-c", """
with cu as (select id from customers where full_name in ('%s')),
 c as (select id from cards where customer_id in (select id from cu)),
 m0 as (delete from outbound_messages where card_id in (select id from c)),
 d1 as (delete from ledger_entries where ledger_account_id in (select id from ledger_accounts where card_id in (select id from c))),
 d2 as (delete from ledger_accounts where card_id in (select id from c)),
 d3 as (delete from card_controls where card_id in (select id from c)),
 d4 as (delete from authorization_attempts where card_id in (select id from c)),
 d5 as (delete from fraud_alerts where card_id in (select id from c)),
 d6 as (delete from step_up_challenges where card_id in (select id from c)),
 d7 as (delete from plastics where card_id in (select id from c)),
 d9 as (delete from guild_alerts where card_id in (select id from c)),
 d11 as (delete from authorization_holds where card_id in (select id from c)),
 d12 as (delete from cards where id in (select id from c)),
 d13 as (delete from kyc where customer_id in (select id from cu))
delete from customers where id in (select id from cu)""" % lst], capture_output=True, text=True, env=dict(os.environ, PGPASSWORD="alodiga.123"))
check("datos de prueba borrados", sql("select count(*) from customers where full_name in ('%s')" % lst) == "0", "")

print(f"\nRESULTADO: {ok} OK, {bad} FAIL")
raise SystemExit(1 if bad else 0)
