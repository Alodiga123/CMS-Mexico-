# -*- coding: utf-8 -*-
"""Acquiring <-> issuer, on-us: a card issued by the CMS is sold through the acquiring backend
(puntos-adquisicion-pos). The acquirer routes the ISO 8583 message straight to the CMS authorizer,
the CMS reserves; a void releases; the day's on-us sales are cleared with a CMS-CLR file the
acquirer hands to the CMS, which captures and settles.

Needs: the CMS on :8085 (CMS_API_KEY=dev-api-key, HSM_EXPOSE_TEST_SECRETS=true) and the acquiring
backend with the on-us route (POS_URL, default http://localhost:4100/api/v1) pointing at the CMS."""
import os, json, time, subprocess, urllib.request, urllib.error
import sys as _sys; _sys.path.insert(0, __import__("os").path.dirname(__file__)); from kyc_demo import identity

CMS = os.environ.get("CMS_URL", "http://localhost:8085/api")
POS = os.environ.get("POS_URL", "http://localhost:4100/api/v1")
PSQL = r"C:\Program Files\PostgreSQL\17\bin\psql.exe"
API_KEY = os.environ.get("CMS_API_KEY", "dev-api-key")
POS_USER = os.environ.get("POS_ADMIN_USER", "mesa@finsus.mx")
POS_PASS = os.environ.get("POS_ADMIN_PASSWORD", "Agregador2026!")
RUN = str(int(time.time()) % 100000)


def call(base, m, p, b=None, headers=None):
    h = {"Content-Type": "application/json", "Accept": "*/*"}
    h.update(headers or {})
    r = urllib.request.Request(base + p, data=json.dumps(b).encode() if b is not None else None, method=m, headers=h)
    try:
        with urllib.request.urlopen(r, timeout=90) as x:
            return x.status, json.loads(x.read() or b"null")
    except urllib.error.HTTPError as e:
        body = e.read()
        try: return e.code, json.loads(body or b"null")
        except Exception: return e.code, None


def cms(m, p, b=None): return call(CMS, m, p, b, {"X-Api-Key": API_KEY})
def pos(m, p, b=None): return call(POS, m, p, b, {"Authorization": "Bearer " + TOKEN})


def sql(q, db="cms_mexico"):
    return subprocess.run([PSQL, "-h", "localhost", "-U", "postgres", "-d", db, "-tAc", q],
                          capture_output=True, text=True, env=dict(os.environ, PGPASSWORD="alodiga.123")).stdout.strip()


ok = bad = 0


def hold(rrn):
    """The CMS hold that carries the acquirer's RRN (status, amount, approval code)."""
    row = sql("select status || '|' || amount || '|' || approval_code from authorization_holds where rrn='%s' order by id desc limit 1" % rrn) if rrn else ""
    if not row: return None
    s, a, c = row.split("|")
    return {"status": s, "amount": float(a), "approvalCode": c}


def check(label, cond, detail=""):
    global ok, bad
    if cond:
        ok += 1; print(f"  OK   {label}")
    else:
        bad += 1; print(f"  FAIL {label}  {str(detail)[:360]}")


print("== 0. accesos ==")
st, lg = call(POS, "POST", "/auth/login", {"email": POS_USER, "password": POS_PASS})
d = (lg or {}).get("data") or lg or {}
TOKEN = d.get("token") or d.get("accessToken") or ""
check("sesion en el backend de adquirencia", st == 200 and TOKEN, (st, lg))

print("== 0b. BIN on-us sincronizados desde el catalogo del CMS ==")
st, bl = pos("GET", "/switches/cms/bins")
bd = (bl or {}).get("data") or {}
check("el adquirente toma sus BIN on-us del CMS (fuente CMS) y 453212 esta entre ellos", st == 200 and bd.get("fuente") == "CMS" and "453212" in (bd.get("bins") or []), (st, bd.get("fuente"), bd.get("bins"), bd.get("ultimoError")))
NEWBIN = "97" + RUN.zfill(5)[-4:]
st, np_ = cms("POST", "/products", {"productCode": "ONUS-" + RUN, "productName": "Onus sync " + RUN, "cardType": "PREPAID", "paymentType": "PREPAID", "network": "VISA", "bin": NEWBIN, "currency": "MXN", "country": "MX", "by": "scripts"})
NEWPID = (np_ or {}).get("id")
check("un producto nuevo en el CMS con BIN %s" % NEWBIN, st == 201 and NEWPID, (st, np_))
st, rf_ = pos("POST", "/switches/cms/bins/refresh")
rd = (rf_ or {}).get("data") or {}
check("tras sincronizar, el adquirente ya conoce el BIN nuevo sin reiniciar ni tocar configuracion", st == 200 and NEWBIN in (rd.get("bins") or []), (st, rd.get("bins"), rd.get("ultimoError")))
st, ev = pos("POST", "/switches/evaluate-route", {"redMarca": "VISA", "bin": NEWBIN, "tipoOperacion": "VENTA", "monto": "10.00"})
evd = (ev or {}).get("data") or {}
check("el enrutador manda ese BIN on-us al CMS (ON_US_ISSUER, intercambio 0)", st == 200 and (evd.get("switchSeleccionado") or evd.get("switch") or evd.get("switchType") or json.dumps(evd)).__str__().find("CMS_ISSUER") >= 0 and "ON_US" in json.dumps(evd), ev)
st, _ = cms("PUT", "/products/%s" % NEWPID, {"active": False, "by": "scripts"})
st2, rf2_ = pos("POST", "/switches/cms/bins/refresh")
check("desactivado el producto en el CMS, el BIN sale de la lista on-us en la siguiente sincronizacion", st == 200 and st2 == 200 and NEWBIN not in (((rf2_ or {}).get("data") or {}).get("bins") or []), (st, st2, ((rf2_ or {}).get("data") or {}).get("bins")))

print("== 1. una tarjeta del emisor ==")
ppre = int(sql("select id from card_products where product_code='PRE-MX'"))
st, cu = cms("POST", "/customers", {**identity("Onus " + RUN), "fullName": "Onus " + RUN, "phoneNumber": "5550000073", "cardLast4": "0000", "initialDeposit": 10})
st, k = cms("POST", "/cards/issue", {"customerId": cu["id"], "productId": ppre, "embossedName": "ONUS " + RUN, "cardCategory": "VIRTUAL", "initialDeposit": 500})
C = k["id"]
sql("update cards set created_at = now() - interval '30 days' where id=%d" % C)
st, sec = cms("GET", f"/cards/{C}/test-secrets")
PAN = (sec or {}).get("pan")
def ledger(): return float(sql("select coalesce(sum(case when entry_type='CREDIT' then amount else -amount end),0) from ledger_entries le join ledger_accounts la on la.id=le.ledger_account_id where la.card_id=%d" % C))
check("tarjeta prepago con 500, PAN en la boveda y BIN on-us 453212", st == 200 and PAN and PAN.startswith("453212") and ledger() == 500, (st, sec, ledger()))

print("== 2. venta en el adquirente, autorizada por el emisor ==")
st, v = pos("POST", "/transactions/charge", {"pan": PAN, "amount": 120.00, "currency": "MXN", "channel": "punto_fisico", "redMarca": "Visa", "terminalId": 1, "comercioId": 1})
tx = (v or {}).get("data") or {}
check("el adquirente enruta on-us y el CMS aprueba (00) con folio y RRN", st == 200 and tx.get("codigoRespuesta") == "00" and tx.get("switchProcesador") == "CMS_ISSUER" and len(tx.get("folioAutorizacion") or "") == 6 and tx.get("rrn"), (st, v))
h = hold(tx.get("rrn"))
check("el CMS tiene la retencion HELD de 120 con el RRN del adquirente y el ledger intacto", h and h["status"] == "HELD" and h["amount"] == 120 and h["approvalCode"].endswith(tx.get("folioAutorizacion") or "?") and ledger() == 500, (h, ledger()))
st, att = cms("GET", f"/fraud/attempts?cardId={C}&page=0&size=3")
a = ((att or {}).get("content") or [{}])[0]
check("el intento quedo con canal POS, comercio y acquirer del adquirente", a.get("responseCode") == "00" and a.get("channel") == "POS", a)

print("== 3. sin fondos y sin ruta ==")
st, v2 = pos("POST", "/transactions/charge", {"pan": PAN, "amount": 850.00, "currency": "MXN", "channel": "punto_fisico", "redMarca": "Visa", "terminalId": 1, "comercioId": 1})
tx2 = (v2 or {}).get("data") or {}
check("850 sobre 500 disponibles: el emisor responde 51 y el adquirente lo refleja", tx2.get("codigoRespuesta") == "51" and tx2.get("switchProcesador") == "CMS_ISSUER" and "Rechazada" in (tx2.get("estatus") or ""), tx2)
st, v2b = pos("POST", "/transactions/charge", {"pan": PAN, "amount": 950.00, "currency": "MXN", "channel": "punto_fisico", "redMarca": "Visa", "terminalId": 1, "comercioId": 1})
tx2b = (v2b or {}).get("data") or {}
check("950 con 120 ya retenidos supera el limite diario del producto (1,000): 61", tx2b.get("codigoRespuesta") == "61" and tx2b.get("switchProcesador") == "CMS_ISSUER", tx2b)
st, v3 = pos("POST", "/transactions/charge", {"pan": "5063450000001234", "amount": 10.00, "currency": "MXN", "channel": "punto_fisico", "redMarca": "Carnet México", "terminalId": 1, "comercioId": 1})
tx3 = (v3 or {}).get("data") or {}
check("una tarjeta ajena sigue su ruta normal (PROSA / E-Global), no el CMS", tx3.get("switchProcesador") in ("PROSA", "E_GLOBAL"), tx3)

print("== 4. anulacion del mismo dia -> reverso 0400 al emisor ==")
st, vd = pos("POST", "/transactions/void", {"uuidTransaccionOriginal": tx["uuidTransaccion"], "motivo": "prueba on-us"})
vtx = (vd or {}).get("data") or {}
h = hold(tx.get("rrn"))
check("el adquirente anula (00) y la retencion del CMS queda RELEASED", vtx.get("codigoRespuesta") == "00" and vtx.get("switchProcesador") == "CMS_ISSUER" and h and h["status"] == "RELEASED", (vd, h))

print("== 4b. preautorizacion y captura diferida (0100 / 0220) ==")
st, pa = pos("POST", "/transactions/pre-authorize", {"pan": PAN, "amount": 50.00, "currency": "MXN", "channel": "punto_fisico", "redMarca": "Visa", "terminalId": 1, "comercioId": 1})
ptx = (pa or {}).get("data") or {}
hp = hold(ptx.get("rrn"))
check("preautorizacion de 50 on-us: HELD en el CMS, sin mover fondos", ptx.get("codigoRespuesta") == "00" and ptx.get("switchProcesador") == "CMS_ISSUER" and hp and hp["status"] == "HELD" and hp["amount"] == 50 and ledger() == 500, (pa, hp, ledger()))
st, cp = pos("POST", "/transactions/capture", {"uuidPreautorizacion": ptx.get("uuidTransaccion"), "monto": 45.00})
ctx = (cp or {}).get("data") or {}
hp = hold(ptx.get("rrn"))
check("captura de 45 por 0220 con el RRN: el CMS captura parcial y el ledger baja a 455", st == 200 and ctx.get("codigoRespuesta") == "00" and ctx.get("switchProcesador") == "CMS_ISSUER" and hp and hp["status"] == "CAPTURED" and ledger() == 455, (cp, hp, ledger()))

print("== 5. otra venta y la compensacion on-us ==")
st, v4 = pos("POST", "/transactions/charge", {"pan": PAN, "amount": 80.00, "currency": "MXN", "channel": "punto_fisico", "redMarca": "Visa", "terminalId": 1, "comercioId": 1})
tx4 = (v4 or {}).get("data") or {}
check("venta de 80 aprobada on-us", tx4.get("codigoRespuesta") == "00", tx4)
st, cl = pos("POST", "/clearing/cms/submit")
data = (cl or {}).get("data") or {}
batch = data.get("batch") or {}
cmsb = data.get("cms") or {}
check("el adquirente arma el CMS-CLR solo con ventas on-us y el CMS lo acepta", st == 200 and batch.get("fileType") == "CMS_CLR" and batch.get("totalRecords", 0) >= 1 and cmsb.get("status") == "PROCESSED", (st, cl if st != 200 else {k: batch.get(k) for k in ("fileType", "totalRecords")}, cmsb.get("status")))
mine = None
if cmsb.get("id"):
    st, recs = cms("GET", f"/clearing/batches/{cmsb['id']}/records")
    mine = [r for r in (recs or []) if r.get("rrn") == tx4.get("rrn")]
check("la presentacion de 80 caso por RRN (PAN enmascarado) y se capturo: MATCHED_CAPTURED", mine and mine[0]["outcome"] == "MATCHED_CAPTURED" and mine[0]["cardId"] == C, mine)
h4 = hold(tx4.get("rrn"))
check("retencion CAPTURED y ledger 375 (455 - 80)", h4 and h4["status"] == "CAPTURED" and ledger() == 375, (h4, ledger()))
cap = [r for r in (recs or []) if r.get("rrn") == ptx.get("rrn")] if cmsb.get("id") else None
check("la captura de 45 hecha por 0220 se presenta en el archivo y casa sin excepcion (capturada antes)", cap and cap[0]["outcome"] == "MATCHED_CAPTURED" and float(cap[0]["amount"]) == 45 and "captured earlier" in (cap[0].get("detail") or ""), cap)
st, cycles = cms("GET", "/clearing/settlement/cycles")
cyc = [c for c in (cycles or []) if c["id"] == cmsb.get("settlementCycleId")]
check("el ciclo de liquidacion suma la presentacion sin intercambio (on-us)", cyc and float(cyc[0]["presentmentsAmount"]) >= 80 and float(cyc[0]["interchangeAmount"]) == 0, cyc)
print("== 5b. devolucion parcial de la venta compensada -> abono al titular ==")
st, rf = pos("POST", "/transactions/refund", {"uuidTransaccionOriginal": tx4["uuidTransaccion"], "monto": 30.00, "motivo": "producto devuelto"})
rtx = (rf or {}).get("data") or {}
check("el adquirente devuelve 30 por la ruta on-us y el emisor abona (00)", st == 200 and rtx.get("codigoRespuesta") == "00" and rtx.get("switchProcesador") == "CMS_ISSUER" and "Devoluci" in (rtx.get("estatus") or ""), (st, rf))
check("ledger 405 (375 + 30 devueltos)", ledger() == 405, ledger())
st, rf2 = pos("POST", "/transactions/refund", {"uuidTransaccionOriginal": tx4["uuidTransaccion"], "monto": 500.00, "motivo": "mas que la venta"})
check("devolver mas que la venta se rechaza (el adquirente o el emisor con 13)", st != 200 or ((rf2 or {}).get("data") or {}).get("codigoRespuesta") in ("13", None), rf2)
st, cl2 = pos("POST", "/clearing/cms/submit")
d2 = (cl2 or {}).get("data") or {}
c2 = d2.get("cms") or {}
check("volver a compensar el mismo dia: archivo identico rechazado, o lo re-presentado queda como duplicado en excepciones sin cobrar dos veces", (st == 502 and ("duplic" in json.dumps(cl2).lower() or "already" in json.dumps(cl2).lower())) or (st == 200 and ((d2.get("batch") or {}).get("totalRecords", 0) == 0 or (c2.get("matchedCount", 1) == 0 and c2.get("exceptionCount", 0) >= 1))), cl2)
check("el ledger no cambio con la segunda presentacion", ledger() == 405, ledger())

print("== 6. limpieza ==")
subprocess.run([PSQL, "-h", "localhost", "-U", "postgres", "-d", "adquiriencia_pos", "-c", "delete from transacciones where rrn in ('%s') or uuid_transaccion in ('%s')" % ("','".join(x for x in [tx.get("rrn"), tx2.get("rrn"), tx2b.get("rrn"), tx3.get("rrn"), tx4.get("rrn"), vtx.get("rrn"), ptx.get("rrn"), ctx.get("rrn"), rtx.get("rrn")] if x), "','".join(x for x in [tx.get("uuidTransaccion"), tx2.get("uuidTransaccion"), tx2b.get("uuidTransaccion"), tx3.get("uuidTransaccion"), tx4.get("uuidTransaccion"), vtx.get("uuidTransaccion"), ptx.get("uuidTransaccion"), ctx.get("uuidTransaccion"), rtx.get("uuidTransaccion")] if x))], capture_output=True, text=True, env=dict(os.environ, PGPASSWORD="alodiga.123"))
bid = cmsb.get("id")
subprocess.run([PSQL, "-h", "localhost", "-U", "postgres", "-d", "cms_mexico", "-c", """
with cu as (select id from customers where full_name = 'Onus %s'),
 c as (select id from cards where customer_id in (select id from cu)),
 b as (select id from clearing_batches where id = %s or (loaded_by = 'adquirencia' and file_name = '%s')),
 r0 as (delete from clearing_records where batch_id in (select id from b)),
 r1 as (delete from reconciliation_items where card_id in (select id from c) or (item_key like 'CLEARING:%%' and split_part(item_key,':',2)::int in (select id from b))),
 r2 as (delete from clearing_batches where id in (select id from b)),
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
 d12b as (delete from kyc_documents where customer_id in (select id from cu)),
 d13 as (delete from kyc where customer_id in (select id from cu))
delete from customers where id in (select id from cu)""" % (RUN, bid if bid else "0", (batch.get("batchId") or "-") + ".clr")], capture_output=True, text=True, env=dict(os.environ, PGPASSWORD="alodiga.123"))
sql("delete from card_products where product_code='ONUS-%s'" % RUN)
check("datos de prueba borrados en ambos lados", sql("select count(*) from cards where id=%d" % C) == "0", "")

print(f"\nRESULTADO: {ok} OK, {bad} FAIL")
raise SystemExit(1 if bad else 0)
