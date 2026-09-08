# -*- coding: utf-8 -*-
"""End-to-end check of disputes: prepaid via the ledger, debit-with-core via Mifos."""
import os, json, base64, ssl, subprocess, hashlib, time, urllib.request, urllib.error

CMS = "http://localhost:8085/api"
MIFOS = "https://corebancario.alocashfintech.com:4433/fineract-provider/api/v1"
ACC = "1433"
PSQL = r"C:\Program Files\PostgreSQL\17\bin\psql.exe"
tok = base64.b64encode(f"{os.environ['FINERACT_USER']}:{os.environ['FINERACT_PASSWORD']}".encode()).decode()
ctx = ssl.create_default_context(); ctx.check_hostname = False; ctx.verify_mode = ssl.CERT_NONE


def req(base, m, p, b=None, h=None, c=None):
    hd = {"Content-Type": "application/json", "Accept": "application/json"}; hd.update(h or {})
    r = urllib.request.Request(base + p, data=json.dumps(b).encode() if b is not None else None, method=m, headers=hd)
    try:
        with urllib.request.urlopen(r, timeout=120, context=c) as x:
            return x.status, json.loads(x.read() or b"null")
    except urllib.error.HTTPError as e:
        try:
            return e.code, json.loads(e.read() or b"null")
        except Exception:
            return e.code, None


cms = lambda m, p, b=None: req(CMS, m, p, b)
mif = lambda m, p, b=None: req(MIFOS, m, p, b, {"Authorization": "Basic " + tok, "Fineract-Platform-TenantId": "default"}, ctx)


def sql(q):
    return subprocess.run([PSQL, "-h", "localhost", "-U", "postgres", "-d", "cms_mexico", "-tAc", q],
                          capture_output=True, text=True, env=dict(os.environ, PGPASSWORD="alodiga.123")).stdout.strip()


def ledger(card):
    q = ("select coalesce(sum(case when entry_type='CREDIT' then amount else -amount end),0) "
         "from ledger_entries le join ledger_accounts la on la.id=le.ledger_account_id where la.card_id=%d" % card)
    return float(sql(q) or 0)


def mavail():
    st, a = mif("GET", f"/savingsaccounts/{ACC}?associations=summary")
    return a["summary"]["availableBalance"]


ok = bad = 0


def check(label, cond, detail=""):
    global ok, bad
    if cond:
        ok += 1; print(f"  OK   {label}")
    else:
        bad += 1; print(f"  FAIL {label}  {str(detail)[:320]}")


def auth_capture(card, amount):
    st, a = cms("POST", "/authorization", {"cardId": card, "amount": amount, "merchantName": "Tienda Online", "merchantId": "M-1"})
    code = a.get("approvalCode")
    cms("POST", f"/authorization/{code}/capture")
    return code


print("== 0. catalogo de razones ==")
st, rs = cms("GET", "/disputes/reasons")
check("12 codigos sembrados", st == 200 and len(rs) == 12, len(rs) if isinstance(rs, list) else rs)

print("== A. PREPAGO (ledger): abono provisional, evidencia sellada, ciclo completo, gana el comercio ==")
ppre = int(sql("select id from card_products where card_type='PREPAID' order by id desc limit 1"))
st, c = cms("POST", "/customers", {"fullName": "Prueba Aclaracion", "phoneNumber": "5550000008", "cardLast4": "9012", "initialDeposit": 300})
st, k = cms("POST", "/cards/issue", {"customerId": c["id"], "productId": ppre, "embossedName": "PRUEBA ACLARACION", "cardCategory": "VIRTUAL", "last4": "9012", "initialDeposit": 300})
card = k["id"]; cms("POST", f"/cards/{card}/status", {"status": "ACTIVE"})
code = auth_capture(card, 120)
check("compra 120 capturada; ledger 180", ledger(card) == 180, ledger(card))
st, d = cms("POST", "/disputes", {"cardId": card, "approvalCode": code, "reasonCode": "13.1", "description": "no llego el paquete", "provisionalCredit": True, "openedBy": "mesa.control"})
check("aclaracion abierta (201) OPENED con plazos", st == 201 and d["status"] == "OPENED" and d["chargebackDeadline"] and d["resolveBy"], (st, d))
did = d["id"]
check("abono provisional: ledger vuelve a 300", ledger(card) == 300, ledger(card))
st, e = cms("POST", f"/disputes/{did}/chargeback", {"by": "mesa"})
check("13.1 exige evidencia: contracargo rechazado 422", st == 422 and e.get("errorCode") == "DISPUTE_EVIDENCE_REQUIRED", (st, e))
texto = "Guia de envio 8837-MX, sin entrega registrada"
st, ev = cms("POST", f"/disputes/{did}/evidence", {"filename": "guia.txt", "contentType": "text/plain", "text": texto, "description": "guia del cliente", "by": "mesa"})
check("evidencia sellada con SHA-256 correcto", st == 201 and ev.get("sha256") == hashlib.sha256(texto.encode()).hexdigest(), ev)
st, cb = cms("POST", f"/disputes/{did}/chargeback", {"acquirerCaseRef": "ACQ-2026-77", "by": "mesa"})
check("contracargo enviado, plazo de representacion fijado", st == 200 and cb["status"] == "CHARGEBACK_SENT" and cb["representmentDeadline"], cb)
st, d2 = cms("POST", "/disputes", {"cardId": card, "approvalCode": code, "reasonCode": "13.1", "provisionalCredit": False})
check("segunda aclaracion sobre el mismo cargo -> 409", st == 409 and d2.get("errorCode") == "DISPUTE_ALREADY_OPEN", (st, d2))
st, rp = cms("POST", f"/disputes/{did}/represent", {"note": "el comercio presento guia firmada", "by": "mesa"})
check("representacion registrada", rp.get("status") == "REPRESENTED", rp)
st, rv = cms("POST", f"/disputes/{did}/resolve", {"outcome": "MERCHANT", "note": "entrega probada", "by": "mesa"})
check("resuelta a favor del comercio", rv.get("status") == "RESOLVED_MERCHANT" and rv.get("creditReversalRef"), rv)
check("se retira el abono provisional: ledger 180", ledger(card) == 180, ledger(card))
st, f = cms("GET", f"/disputes/{did}/file")
check("expediente: autorizacion, evidencia con hash y linea de tiempo",
      f.get("authorization", {}).get("approvalCode") == code and len(f.get("evidence", [])) == 1
      and any(t["action"] == "CHARGEBACK_SENT" for t in f.get("timeline", [])), {k: f.get(k) for k in ("status", "evidence")})
st, ev2 = cms("POST", f"/disputes/{did}/evidence", {"filename": "tarde.txt", "text": "x"})
check("evidencia sobre caso cerrado -> 409", st == 409, (st, ev2))
st, raw = cms("GET", f"/disputes/{did}/evidence")
check("la evidencia se puede listar con su hash", st == 200 and raw and raw[0]["sha256"] == ev["sha256"], raw)

print("== B. PREPAGO: sin abono provisional, gana el cliente ==")
code2 = auth_capture(card, 50)
check("compra 50 capturada; ledger 130", ledger(card) == 130, ledger(card))
st, d = cms("POST", "/disputes", {"cardId": card, "approvalCode": code2, "reasonCode": "AC-01", "provisionalCredit": False})
did2 = d["id"]
check("sin abono: ledger sigue en 130", ledger(card) == 130, ledger(card))
st, rv = cms("POST", f"/disputes/{did2}/resolve", {"outcome": "CUSTOMER", "note": "sin respuesta del comercio"})
check("a favor del cliente: reembolso, ledger 180", rv.get("status") == "RESOLVED_CUSTOMER" and ledger(card) == 180, (rv.get("status"), ledger(card)))

print("== C. validaciones ==")
st, a = cms("POST", "/authorization", {"cardId": card, "amount": 10, "merchantName": "X", "merchantId": "M"})
held = a["approvalCode"]
st, d = cms("POST", "/disputes", {"cardId": card, "approvalCode": held, "reasonCode": "AC-01"})
check("aclaracion sobre autorizacion no capturada -> 409", st == 409 and d.get("errorCode") == "DISPUTE_NOT_SETTLED", (st, d))
st, d = cms("POST", "/disputes", {"cardId": card, "approvalCode": code, "reasonCode": "ZZ-99"})
check("codigo de razon inexistente -> 404", st == 404, st)

print("== D. plazos: vencidos y proximos, con el job ==")
code3 = auth_capture(card, 20)
st, d = cms("POST", "/disputes", {"cardId": card, "approvalCode": code3, "reasonCode": "AC-02"})
did3 = d["id"]
sql("update disputes set chargeback_deadline = current_date - 1 where id=%d" % did3)
st, due = cms("GET", "/disputes/due?withinDays=3")
check("aparece en los que vencen", any(x["id"] == did3 for x in due), [x["id"] for x in due])
deadline = time.time() + 60; br = False
while time.time() < deadline:
    st, x = cms("GET", f"/disputes/{did3}"); br = x.get("deadlineBreached")
    if br:
        break
    time.sleep(4)
check("el job la marca como vencida sin intervencion", br is True, br)

print("== E. DEBITO CON CORE (Mifos 1433): el abono provisional es un deposito real ==")
card_core = int(sql("select id from cards where external_account_id='%s' order by id limit 1" % ACC))
m0 = mavail(); code4 = auth_capture(card_core, 3); m1 = mavail()
check("compra 3 capturada: Mifos -3", m1 == m0 - 3, (m0, m1))
st, d = cms("POST", "/disputes", {"cardId": card_core, "approvalCode": code4, "reasonCode": "AC-01", "provisionalCredit": True, "openedBy": "mesa"})
did4 = d["id"]
check("abono provisional depositado en Mifos: vuelve a m0", mavail() == m0 and str(d.get("creditRef", "")).isdigit(), (mavail(), d.get("creditRef")))
st, rv = cms("POST", f"/disputes/{did4}/resolve", {"outcome": "MERCHANT", "note": "cargo legitimo"})
check("gana el comercio: retiro real en Mifos, queda m0-3", rv.get("status") == "RESOLVED_MERCHANT" and mavail() == m0 - 3, (rv.get("status"), mavail()))
st, f = cms("GET", f"/disputes/{did4}/file")
check("expediente del caso core con referencias de deposito y retiro", f["money"]["creditRef"] and f["money"]["creditReversalRef"], f.get("money"))

print(f"\nRESULTADO: {ok} OK, {bad} FAIL")
raise SystemExit(1 if bad else 0)
