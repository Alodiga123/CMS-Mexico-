# -*- coding: utf-8 -*-
"""End-to-end check of the multi-product authorizer over HTTP (CMS-Mexico on 8085)."""
import json, os, subprocess, sys, time, urllib.request, urllib.error
import sys as _sys; _sys.path.insert(0, __import__("os").path.dirname(__file__)); from kyc_demo import identity

B = "http://localhost:8085/api"
PSQL = r"C:\Program Files\PostgreSQL\17\bin\psql.exe"
ok = 0; bad = 0
ACC = f"SAV-{int(time.time())}"  # cuenta del core unica por corrida: el simulador conserva retenciones entre corridas

def http(method, path, body=None, headers=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(B + path, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    req.add_header("X-Api-Key", os.environ.get("CMS_API_KEY", "dev-api-key"))
    for k, v in (headers or {}).items(): req.add_header(k, v)
    try:
        with urllib.request.urlopen(req, timeout=20) as r:
            return r.status, json.loads(r.read() or b"null")
    except urllib.error.HTTPError as e:
        try: return e.code, json.loads(e.read() or b"null")
        except Exception: return e.code, None

def sql(q):
    env = dict(os.environ, PGPASSWORD="alodiga.123")
    r = subprocess.run([PSQL, "-h", "localhost", "-U", "postgres", "-d", "cms_mexico", "-tAc", q],
                       capture_output=True, text=True, env=env)
    if r.returncode != 0: print("  [sql error]", r.stderr.strip()[:200])
    return r.stdout.strip()

def check(label, cond, detail=""):
    global ok, bad
    if cond: ok += 1; print(f"  OK   {label}")
    else:    bad += 1; print(f"  FAIL {label}  {detail}")

def product(code, name, ctype, ptype, credit=None, daily=None):
    """Find the product by its code, or create it (POST honours productCode; a repeat is a 409)."""
    st, r = http("GET", f"/products/by-code/{code}")
    if st != 200:
        st, r = http("POST", "/products", {"productCode": code, "productName": name, "cardType": ctype, "paymentType": ptype,
                                            "network": "VISA", "bin": "453212", "currency": "MXN", "creditLimit": credit,
                                            "dailyLimit": daily, "country": "MX", "by": "verify_authorizer"})
        assert st == 201 and r.get("productCode") == code, (st, r)
    set_limits(r["id"], daily)
    return r["id"]

def set_limits(pid, daily=None, weekly=None, monthly=None):
    sql(f"update card_products set daily_limit={daily or 'NULL'}, weekly_limit={weekly or 'NULL'}, monthly_limit={monthly or 'NULL'} where id={pid}")

def new_card(pid, deposit, last4):
    st, c = http("POST", "/customers", {**identity(f"Titular {last4}"), "fullName": f"Titular {last4}", "phoneNumber": "5550000000",
                                         "cardLast4": last4, "initialDeposit": deposit})
    cid = c["id"]
    st, k = http("POST", "/cards/issue", {"customerId": cid, "productId": pid, "embossedName": f"TITULAR {last4}",
                                          "cardCategory": "VIRTUAL", "last4": last4, "initialDeposit": deposit})
    card = k["id"]
    http("POST", f"/cards/{card}/status", {"status": "ACTIVE"})
    return card

def auth(card, amount, key=None, merchant="Cafeteria Offline"):
    h = {"Idempotency-Key": key} if key else {}
    st, r = http("POST", "/authorization", {"cardId": card, "amount": amount, "merchantName": merchant,
                                             "merchantId": "4152310077007"}, h)
    return st, r

print("== A. PREPAGO (saldo en el ledger interno) ==")
p_pre = product("PRE-MX", "Prepago MX", "PREPAID", "PREPAID", daily=None)
c_pre = new_card(p_pre, 300, "1001")
st, r = auth(c_pre, 120)
check("120 aprobada con 00", st == 200 and r["approved"] and r["responseCode"] == "00", r)
check("disponible tras retener = 180", str(r["amount"]) in ("180", "180.0", "180.00"), r.get("amount"))
code_a = r["approvalCode"]
st, r = auth(c_pre, 200)
check("200 declinada 51 (solo 180 libres)", st == 200 and not r["approved"] and r["responseCode"] == "51", r)
st, r = http("POST", f"/authorization/{code_a}/capture")
check("captura -> CAPTURED", st == 200 and r["status"] == "CAPTURED", r)
bal = sql(f"select coalesce(sum(case when entry_type='CREDIT' then amount else -amount end),0) from ledger_entries le join ledger_accounts la on la.id=le.ledger_account_id where la.card_id={c_pre}")
check("ledger debitado solo al capturar: saldo 180", bal.startswith("180"), bal)
st, r = http("POST", f"/authorization/{code_a}/reverse")
check("reversar lo ya capturado -> 409", st == 409 and r and r.get("errorCode") == "HOLD_INVALID_STATE", (st, r))

print("== B. DEBITO CON SALDO EN EL CORE (simulado, cuenta con 2500, limite diario 1500) ==")
p_deb = 1  # PROD-ALODIGA-DEB: DEBIT / POSTPAID, dailyLimit 1500
c_deb = new_card(p_deb, 0, "2002")
st, r = auth(c_deb, 100)
check("sin cuenta vinculada -> 422 CARD_NOT_LINKED_TO_CORE", st == 422 and r.get("errorCode") == "CARD_NOT_LINKED_TO_CORE", (st, r))
sql(f"update cards set external_account_id='{ACC}-1' where id={c_deb}")
st, r = auth(c_deb, 1000)
check("1000 aprobada contra el core", st == 200 and r["approved"], r)
code_b = r["approvalCode"]
st, h = http("GET", f"/authorization/{code_b}")
check("retencion con referencia del core (SIMHOLD-*)", str(h.get("externalRef", "")).startswith("SIMHOLD-"), h.get("externalRef"))
st, r = auth(c_deb, 600)
check("600 declinada 61 (limite diario 1500: 1000 usados)", r["responseCode"] == "61", r)
st, r = http("POST", f"/authorization/{code_b}/reverse")
check("reverso -> RELEASED", st == 200 and r["status"] == "RELEASED", r)
st, r = auth(c_deb, 1400)
check("1400 aprobada tras el reverso (reservas liberadas no cuentan)", r["approved"], r)
st, r = auth(c_deb, 1200)
check("1200 declinada 61: el limite diario (1400+1200>1500) manda antes que el saldo", r["responseCode"] == "61", r)
print("-- B2. mismo producto sin tope diario: el 51 debe venir del core --")
p_deb2 = product("DEB-MX", "Debito core MX", "DEBIT", "POSTPAID", daily=10000)
c_deb2 = new_card(p_deb2, 0, "2003")
sql(f"update cards set external_account_id='{ACC}-2' where id={c_deb2}")
st, r = auth(c_deb2, 2600)
check("2600 declinada 51 (core simulado: 2500)", r["responseCode"] == "51" and "available 2500" in r["message"], r)
st, r = auth(c_deb2, 2000)
check("2000 aprobada, disponible 500", r["approved"] and str(r["amount"]).startswith("500"), r)
st, r = auth(c_deb2, 600)
check("600 declinada 51 (quedan 500 tras la retencion del core)", r["responseCode"] == "51", r)

print("== C. CREDITO (linea 5000) ==")
p_cre = product("CRE-MX", "Credito MX", "CREDIT", "POSTPAID", credit=5000, daily=10000)
set_limits(p_cre, daily=10000)
c_cre = new_card(p_cre, 0, "3004")
st, r = auth(c_cre, 4000)
check("4000 aprobada, disponible 1000", r["approved"] and str(r["amount"]).startswith("1000"), r)
check("cardType CREDIT en la respuesta", r.get("cardType") == "CREDIT", r.get("cardType"))
st, r = auth(c_cre, 1500)
check("1500 declinada 51 (linea agotada)", r["responseCode"] == "51", r)

print("== D. IDEMPOTENCIA ==")
st, r1 = auth(c_cre, 50, key="idem-001")
st, r2 = auth(c_cre, 50, key="idem-001")
check("misma clave -> mismo approvalCode", r1["approvalCode"] == r2["approvalCode"], (r1.get("approvalCode"), r2.get("approvalCode")))
n = sql(f"select count(*) from authorization_holds where idempotency_key='idem-001'")
check("una sola retencion en la base", n == "1", n)

print("== E. TARJETA BLOQUEADA ==")
http("POST", f"/cards/{c_cre}/status", {"status": "BLOCKED"})
st, r = auth(c_cre, 10)
check("bloqueada -> 62 sin tocar fondos", st == 200 and r["responseCode"] == "62", r)

print(f"\nRESULTADO: {ok} OK, {bad} FAIL")
sys.exit(1 if bad else 0)
