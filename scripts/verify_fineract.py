# -*- coding: utf-8 -*-
"""Write-path check of the authorizer against a real Fineract/Mifos core.

Runs against a live CMS on 8085 started with core.mode=fineract. Uses a dedicated
test savings account: never point it at a real customer's account.

Env: FINERACT_USER, FINERACT_PASSWORD, FINERACT_URL (api/v1 base), FINERACT_SAVINGS_ID
"""
import os, json, base64, ssl, subprocess, sys, urllib.request, urllib.error
import sys as _sys; _sys.path.insert(0, __import__("os").path.dirname(__file__)); from kyc_demo import identity

CMS = os.environ.get("CMS_URL", "http://localhost:8085/api")
MIFOS = os.environ["FINERACT_URL"].rstrip("/")
SID = os.environ["FINERACT_SAVINGS_ID"]
PSQL = os.environ.get("PSQL", r"C:\Program Files\PostgreSQL\17\bin\psql.exe")
tok = base64.b64encode(f"{os.environ['FINERACT_USER']}:{os.environ['FINERACT_PASSWORD']}".encode()).decode()
ctx = ssl.create_default_context(); ctx.check_hostname = False; ctx.verify_mode = ssl.CERT_NONE

def req(base, m, p, b=None, h=None, c=None):
    hd = {"Content-Type": "application/json", "X-Api-Key": os.environ.get("CMS_API_KEY", "dev-api-key"), "Accept": "application/json"}; hd.update(h or {})
    r = urllib.request.Request(base + p, data=json.dumps(b).encode() if b is not None else None, method=m, headers=hd)
    try:
        with urllib.request.urlopen(r, timeout=60, context=c) as x: return x.status, json.loads(x.read() or b"null")
    except urllib.error.HTTPError as e:
        try: return e.code, json.loads(e.read() or b"null")
        except Exception: return e.code, None

cms = lambda m, p, b=None: req(CMS, m, p, b)
mif = lambda m, p, b=None: req(MIFOS, m, p, b, {"Authorization": "Basic " + tok, "Fineract-Platform-TenantId": os.environ.get("FINERACT_TENANT", "default")}, ctx)

def sql(q):
    env = dict(os.environ, PGPASSWORD=os.environ.get("DB_PASSWORD", ""))
    return subprocess.run([PSQL, "-h", "localhost", "-U", "postgres", "-d", "cms_mexico", "-tAc", q], capture_output=True, text=True, env=env).stdout.strip()

def avail():
    st, a = mif("GET", f"/savingsaccounts/{SID}?associations=summary"); return a["summary"]["availableBalance"]

def withdrawals():
    st, a = mif("GET", f"/savingsaccounts/{SID}?associations=transactions")
    return [float(t["amount"]) for t in a.get("transactions", []) if t["transactionType"]["value"] == "Withdrawal" and not t.get("reversed")]

ok = bad = 0
def check(l, c, d=""):
    global ok, bad
    if c: ok += 1; print(f"  OK   {l}")
    else: bad += 1; print(f"  FAIL {l}  {str(d)[:300]}")

pid = sql("select id from card_products where card_type='DEBIT' and payment_type='POSTPAID' and daily_limit>=10000 order by id desc limit 1") or "1"
st, c = cms("POST", "/customers", {**identity("Prueba Fineract"), "fullName": "Prueba Fineract", "phoneNumber": "5550000000", "cardLast4": "9009", "initialDeposit": 0})
st, k = cms("POST", "/cards/issue", {"customerId": c["id"], "productId": int(pid), "embossedName": "PRUEBA FINERACT", "cardCategory": "VIRTUAL", "last4": "9009", "initialDeposit": 0})
card = k["id"]; cms("POST", f"/cards/{card}/status", {"status": "ACTIVE"}); sql(f"update cards set external_account_id='{SID}' where id={card}")
a0 = avail(); print(f"  card {card} -> Mifos savings {SID}; available = {a0}")

print("== 1. hold 30 ==")
st, r = cms("POST", "/authorization", {"cardId": card, "amount": 30, "merchantName": "Prueba retencion", "merchantId": "TEST"})
check("approved 00", st == 200 and r.get("responseCode") == "00", r); code1 = r.get("approvalCode")
check("Mifos available = a0 - 30", avail() == a0 - 30, avail())
print("== 2. reverse ==")
st, r = cms("POST", f"/authorization/{code1}/reverse"); check("RELEASED", st == 200 and r.get("status") == "RELEASED", r)
check("Mifos available back to a0", avail() == a0, avail())
print("== 3. hold 25 right after a server-dated release ==")
st, r = cms("POST", "/authorization", {"cardId": card, "amount": 25, "merchantName": "Prueba captura", "merchantId": "TEST"})
check("approved 00", st == 200 and r.get("responseCode") == "00", r); code2 = r.get("approvalCode")
print("== 4. capture (withdraw, then release) ==")
n0 = len(withdrawals())
st, r = cms("POST", f"/authorization/{code2}/capture"); check("CAPTURED", st == 200 and r.get("status") == "CAPTURED", r)
check("Mifos available = a0 - 25", avail() == a0 - 25, avail())
check("one new Withdrawal of 25 in the core", len(withdrawals()) == n0 + 1, withdrawals())
print(f"\nRESULT: {ok} OK, {bad} FAIL"); sys.exit(1 if bad else 0)
