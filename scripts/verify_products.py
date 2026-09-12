# -*- coding: utf-8 -*-
"""End-to-end check of card products: productCode honoured, generated when absent, unique,
validated input, rename, deactivate, lookup by code, audit."""
import os, json, time, subprocess, urllib.request, urllib.error
import sys as _sys; _sys.path.insert(0, __import__("os").path.dirname(__file__)); from kyc_demo import identity

CMS = "http://localhost:8085/api"
PSQL = r"C:\Program Files\PostgreSQL\17\bin\psql.exe"
RUN = str(int(time.time()) % 100000)


def http(m, p, b=None):
    r = urllib.request.Request(CMS + p, data=json.dumps(b).encode() if b is not None else None, method=m,
                               headers={"Content-Type": "application/json", "X-Api-Key": os.environ.get("CMS_API_KEY", "dev-api-key"), "Accept": "*/*"})
    try:
        with urllib.request.urlopen(r, timeout=60) as x:
            return x.status, json.loads(x.read() or b"null")
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


base = {"productName": "Prueba " + RUN, "cardType": "prepaid", "paymentType": "prepaid", "network": "visa",
        "bin": "453299", "currency": "mxn", "country": "mx", "dailyLimit": 500, "by": "verify_products"}
CODE = "E2E-" + RUN

print("== 1. el codigo se respeta ==")
st, p = http("POST", "/products", dict(base, productCode=" e2e-" + RUN + " "))
check("201 y productCode normalizado en mayusculas", st == 201 and p["productCode"] == CODE and p["currency"] == "MXN" and p["country"] == "MX" and p["cardType"] == "PREPAID", (st, p))
pid = p["id"]
st, dup = http("POST", "/products", dict(base, productCode=CODE.lower()))
check("mismo codigo otra vez -> 409 PRODUCT_CODE_IN_USE", st == 409 and dup.get("errorCode") == "PRODUCT_CODE_IN_USE", (st, dup))
st, bc = http("GET", f"/products/by-code/{CODE.lower()}")
check("GET /by-code lo encuentra sin importar mayusculas", st == 200 and bc["id"] == pid, (st, bc))
st, nf = http("GET", "/products/by-code/NO-EXISTE-" + RUN); check("codigo inexistente -> 404", st == 404 and nf.get("errorCode") == "PRODUCT_NOT_FOUND", (st, nf))
au = sql("select username from audit_logs where action='CREATE_PRODUCT' and entity_id='%s'" % CODE)
check("la creacion queda auditada con quien la pidio", au == "verify_products", au)

print("== 2. sin codigo se genera uno legible y unico ==")
before = int(sql("select count(*) from card_products where product_code like 'PRE-MX-%'"))
st, g1 = http("POST", "/products", dict(base, productName="Generado A " + RUN))
st, g2 = http("POST", "/products", dict(base, productName="Generado B " + RUN))
check("PRE-MX-NNN consecutivos", g1["productCode"].startswith("PRE-MX-") and g2["productCode"].startswith("PRE-MX-") and int(g2["productCode"][-3:]) == int(g1["productCode"][-3:]) + 1, (g1["productCode"], g2["productCode"]))
st, g3 = http("POST", "/products", dict(base, productName="Credito " + RUN, cardType="CREDIT", paymentType="POSTPAID", creditLimit=5000, country="USA"))
check("CRE-USA-NNN para credito", g3["productCode"].startswith("CRE-USA-") and float(g3["creditLimit"]) == 5000, g3["productCode"])
check("ya no se emiten codigos PROD-<millis>", not any(p["productCode"].startswith("PROD-") and int(sql("select count(*) from card_products where product_code='%s' and created_at > now() - interval '5 minutes'" % p["productCode"])) for p in (g1, g2, g3)), (g1["productCode"], g2["productCode"], g3["productCode"]))

print("== 3. entradas invalidas -> 400, nunca 500 ==")
for label, body, code in [
    ("codigo con caracteres invalidos", dict(base, productCode="PRE/MX"), "PRODUCT_CODE_INVALID"),
    ("codigo de una letra", dict(base, productCode="X"), "PRODUCT_CODE_INVALID"),
    ("cardType desconocido", dict(base, cardType="GOLD"), "PRODUCT_INVALID"),
    ("network desconocida", dict(base, network="AMEX"), "PRODUCT_INVALID"),
    ("bin de 4 digitos", dict(base, bin="4532"), "PRODUCT_INVALID"),
    ("nombre en blanco", dict(base, productName="  "), "PRODUCT_INVALID"),
    ("limite negativo", dict(base, dailyLimit=-5), "PRODUCT_INVALID"),
    ("sin tipo", {k: v for k, v in base.items() if k != "cardType"}, "PRODUCT_INVALID"),
]:
    st, r = http("POST", "/products", body)
    check(label + " -> 400 " + code, st == 400 and r.get("errorCode") == code, (st, r))

print("== 4. actualizacion: renombrar codigo, chocar, desactivar, 404 ==")
st, u = http("PUT", f"/products/{pid}", {"productCode": CODE + "-V2", "productName": "Prueba v2 " + RUN, "by": "ops"})
check("renombrado a -V2 y nombre nuevo, resto intacto", st == 200 and u["productCode"] == CODE + "-V2" and u["productName"] == "Prueba v2 " + RUN and u["bin"] == "453299" and float(u["dailyLimit"]) == 500, (st, u))
st, clash = http("PUT", f"/products/{g1['id']}", {"productCode": CODE + "-V2"})
check("renombrar otro producto al mismo codigo -> 409", st == 409 and clash.get("errorCode") == "PRODUCT_CODE_IN_USE", (st, clash))
st, same = http("PUT", f"/products/{pid}", {"productCode": (CODE + "-v2").lower(), "dailyLimit": 750})
check("mandar el propio codigo no choca; el limite cambia", st == 200 and same["productCode"] == CODE + "-V2" and float(same["dailyLimit"]) == 750, (st, same))
st, off = http("PUT", f"/products/{pid}", {"active": False, "by": "ops"})
check("desactivado", st == 200 and off["active"] is False, (st, off))
st, badu = http("PUT", f"/products/{pid}", {"bin": "12"}); check("PUT con bin invalido -> 400", st == 400 and badu.get("errorCode") == "PRODUCT_INVALID", (st, badu))
st, none = http("PUT", "/products/99999999", {"productName": "x"}); check("PUT a producto inexistente -> 404", st == 404 and none.get("errorCode") == "PRODUCT_NOT_FOUND", (st, none))
st, none = http("GET", "/products/99999999"); check("GET a producto inexistente -> 404", st == 404 and none.get("errorCode") == "PRODUCT_NOT_FOUND", (st, none))
au = sql("select count(*) from audit_logs where action='UPDATE_PRODUCT' and entity_id='%s'" % (CODE + "-V2"))
check("las actualizaciones quedan auditadas", int(au) >= 3, au)

print("== 5. el producto sigue siendo usable: una tarjeta sobre el generado ==")
st, c = http("POST", "/customers", {**identity("Titular " + RUN), "fullName": "Titular " + RUN, "phoneNumber": "5550000030", "cardLast4": "7" + RUN[-3:], "initialDeposit": 10})
st, k = http("POST", "/cards/issue", {"customerId": c["id"], "productId": g1["id"], "embossedName": "TITULAR", "cardCategory": "VIRTUAL", "last4": "7" + RUN[-3:], "initialDeposit": 100})
check("tarjeta emitida sobre PRE-MX-NNN", st in (200, 201) and k.get("id"), (st, k))

print("== 6. limpieza: este script no deja productos ni tarjetas de prueba ==")
codes = ",".join("'%s'" % x for x in [CODE + "-V2", g1["productCode"], g2["productCode"], g3["productCode"]])
sql(f"""
with p as (select id from card_products where product_code in ({codes})),
     c as (select id from cards where product_id in (select id from p)),
     la as (select id from ledger_accounts where card_id in (select id from c)),
     d1 as (delete from ledger_entries where ledger_account_id in (select id from la)),
     d2 as (delete from ledger_accounts where id in (select id from la)),
     d3 as (delete from card_controls where card_id in (select id from c)),
     d4 as (delete from authorization_attempts where card_id in (select id from c)),
     d5 as (delete from fraud_alerts where card_id in (select id from c)),
     d6 as (delete from step_up_challenges where card_id in (select id from c)),
     d7 as (delete from plastics where card_id in (select id from c)),
     d8 as (delete from disputes where card_id in (select id from c)),
     d9 as (delete from authorization_holds where card_id in (select id from c)),
     d10 as (delete from cards where id in (select id from c))
delete from card_products where id in (select id from p)
""")
left = sql(f"select count(*) from card_products where product_code in ({codes})")
check("productos de prueba borrados con sus tarjetas", left == "0", left)

print(f"\nRESULTADO: {ok} OK, {bad} FAIL")
raise SystemExit(1 if bad else 0)
