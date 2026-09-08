# -*- coding: utf-8 -*-
"""Clearing and settlement end to end: the authorizer reserves, the (simulated) network presents
a clearing file with the usual anomalies, the CMS matches, posts, flags and reverses, and the
settlement cycle nets the position, closes with a sealed file and is marked paid."""
import os, json, time, hashlib, subprocess, urllib.request, urllib.error

CMS = "http://localhost:8085/api"
PSQL = r"C:\Program Files\PostgreSQL\17\bin\psql.exe"
API_KEY = os.environ.get("CMS_API_KEY", "dev-api-key")
RUN = str(int(time.time()) % 100000)


def http(m, p, b=None, raw=False):
    h = {"Content-Type": "application/json", "Accept": "*/*", "X-Api-Key": API_KEY}
    r = urllib.request.Request(CMS + p, data=json.dumps(b).encode() if b is not None else None, method=m, headers=h)
    try:
        with urllib.request.urlopen(r, timeout=90) as x:
            body = x.read()
            return (x.status, body, dict(x.headers)) if raw else (x.status, json.loads(body or b"null"))
    except urllib.error.HTTPError as e:
        body = e.read()
        if raw: return e.code, body, dict(e.headers)
        try: return e.code, json.loads(body or b"null")
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
        bad += 1; print(f"  FAIL {label}  {str(detail)[:360]}")


ppre = int(sql("select id from card_products where product_code='PRE-MX'"))
st, c = http("POST", "/customers", {"fullName": "Clearing " + RUN, "phoneNumber": "5550000070", "cardLast4": "0000", "initialDeposit": 10})
st, k = http("POST", "/cards/issue", {"customerId": c["id"], "productId": ppre, "embossedName": "CLEARING " + RUN, "cardCategory": "VIRTUAL", "initialDeposit": 500})
C = k["id"]
sql("update cards set created_at = now() - interval '30 days' where id=%d" % C)
def ledger(): return float(sql("select coalesce(sum(case when entry_type='CREDIT' then amount else -amount end),0) from ledger_entries le join ledger_accounts la on la.id=le.ledger_account_id where la.card_id=%d" % C))
check("tarjeta prepago con 500 en el ledger y PAN en la boveda", ledger() == 500 and sql("select pan_hash is not null from cards where id=%d" % C) == "t", ledger())

print("== 1. el autorizador reserva ==")
codes = []
for amt, m in ((30, "M-A"), (45, "M-B"), (60, "M-C")):
    st, r = http("POST", "/authorization", {"cardId": C, "amount": amt, "merchantName": "Tienda " + m, "merchantId": m + "-" + RUN, "channel": "POS"})
    codes.append(r["approvalCode"])
check("tres retenciones vivas (30, 45, 60) y el ledger intacto (reservar no debita)", len(codes) == 3 and ledger() == 500, (codes, ledger()))
# a settled purchase with a chargeback sent, for the chargeback record
st, r = http("POST", "/authorization", {"cardId": C, "amount": 80, "merchantName": "Tienda M-D", "merchantId": "M-D-" + RUN, "channel": "POS"}); code_d = r["approvalCode"]
http("POST", f"/authorization/{code_d}/capture")
st, d = http("POST", "/disputes", {"cardId": C, "approvalCode": code_d, "reasonCode": "13.1", "description": "clearing e2e", "provisionalCredit": False, "openedBy": "mesa"})
http("POST", f"/disputes/{d['id']}/evidence", {"filename": "guia.txt", "contentType": "text/plain", "text": "guia", "description": "guia", "by": "mesa"})
st, cb = http("POST", f"/disputes/{d['id']}/chargeback", {"acquirerCaseRef": "ACQ-" + RUN, "by": "mesa"})
check("una compra de 80 capturada con contracargo enviado (ledger 420)", cb["status"] == "CHARGEBACK_SENT" and ledger() == 420, (cb.get("status"), ledger()))

print("== 2. la red presenta el ciclo: archivo simulado con anomalias ==")
st, sim = http("POST", "/clearing/simulate", {"network": "VISA", "anomalies": "over,duplicate,unknown,reversal,chargeback,fee", "cardId": C, "ingest": True, "by": "operaciones"})
check("archivo generado y cargado (201 lote PROCESSED)", st == 200 and sim["batch"]["status"] == "PROCESSED", (st, sim if st != 200 else sim["batch"]))
b = sim["batch"]; content = sim["content"]
check("el archivo lleva cabecera, registros y cola coherentes", content.startswith("HDR|CMS-CLR|1.0|VISA|") and content.rstrip().split("\n")[-1].startswith("TRL|%d|" % b["recordCount"]), content[:120])
check("el lote esta sellado con el SHA-256 del archivo", b["sha256"] == hashlib.sha256(content.encode()).hexdigest(), b["sha256"])
st, recs = http("GET", f"/clearing/batches/{b['id']}/records")
by = {}
for r in recs: by.setdefault(r["outcome"], []).append(r)
print("     resultados:", {k: len(v) for k, v in by.items()})
check("la presentacion de 30 llego por 45 (sobre tolerancia): AMOUNT_MISMATCH, se capturan 30 y quedan 15 por conciliar", len(by.get("AMOUNT_MISMATCH", [])) == 1 and by["AMOUNT_MISMATCH"][0]["amount"] == 45 and by["AMOUNT_MISMATCH"][0]["approvalCode"] == codes[0], by.get("AMOUNT_MISMATCH"))
check("las de 45 y 60 casan y se capturan: MATCHED_CAPTURED", sorted(r["amount"] for r in by.get("MATCHED_CAPTURED", [])) == [45, 60], by.get("MATCHED_CAPTURED"))
check("la presentacion duplicada de 60: ALREADY_CAPTURED", len(by.get("ALREADY_CAPTURED", [])) == 1 and by["ALREADY_CAPTURED"][0]["amount"] == 60, by.get("ALREADY_CAPTURED"))
check("el PAN desconocido: NO_CARD", len(by.get("NO_CARD", [])) == 1, by.get("NO_CARD"))
check("el reverso de compensacion de 60: REVERSED (abono al titular)", len(by.get("REVERSED", [])) == 1 and by["REVERSED"][0]["amount"] == 60, by.get("REVERSED"))
check("el contracargo 13.1 casa con la aclaracion: DISPUTE_LINKED", len(by.get("DISPUTE_LINKED", [])) == 1 and "dispute #%d" % d["id"] in by["DISPUTE_LINKED"][0]["detail"], by.get("DISPUTE_LINKED"))
check("la cuota de red: FEE_BOOKED", len(by.get("FEE_BOOKED", [])) == 1, by.get("FEE_BOOKED"))
holds = sql("select string_agg(approval_code||':'||status||':'||coalesce(captured_amount::text,'-'), ',' order by id) from authorization_holds where card_id=%d" % C)
check("retenciones: 30 capturada por 30, 45 por 45, 60 por 60, 80 capturada", all(x in holds for x in [codes[0] + ":CAPTURED:30.00", codes[1] + ":CAPTURED:45.00", codes[2] + ":CAPTURED:60.00", code_d + ":CAPTURED:80.00"]), holds)
check("ledger: 420 - 30 - 45 - 60 + 60 (reverso) = 345", ledger() == 345, ledger())
items = sql("select string_agg(type, ',' order by id) from reconciliation_items where item_key like 'CLEARING:%d:%%'" % b["id"])
check("excepciones abiertas en conciliacion: monto distinto, duplicado, PAN desconocido", "CLEARING_AMOUNT_MISMATCH" in items and "CLEARING_DUPLICATE" in items and "CLEARING_NO_CARD" in items, items)
check("totales del lote: 3 presentaciones por 150 (45+45+60), reversos 60, contracargos 80, cuotas 38.70 + intercambio", b["presentmentsCount"] == 3 and float(b["presentmentsAmount"]) == 150 and float(b["reversalsAmount"]) == 60 and float(b["chargebacksAmount"]) == 80 and float(b["feesAmount"]) > 38.70, {k: b[k] for k in ("presentmentsCount", "presentmentsAmount", "reversalsAmount", "chargebacksAmount", "feesAmount", "matchedCount", "exceptionCount")})
st, dup = http("POST", "/clearing/files", {"fileName": "again.clr", "content": content, "by": "ops"})
check("cargar el mismo archivo otra vez -> 409", st == 409 and dup.get("errorCode") == "CLEARING_FILE_DUPLICATE", (st, dup))
st, badf = http("POST", "/clearing/files", {"fileName": "bad.clr", "content": content.replace("TRL|", "TRL|9"), "by": "ops"})
check("cola inconsistente -> 422", st == 422 and badf.get("errorCode") == "CLEARING_FILE_INVALID", (st, badf))
st, raw, hdr = http("GET", f"/clearing/batches/{b['id']}/file", raw=True)
check("descarga del archivo con el sello en cabecera", st == 200 and hdr.get("X-File-SHA256") == b["sha256"] and raw.decode() == content, st)
st, exc = http("GET", "/clearing/exceptions"); check("las excepciones se listan para la mesa", st == 200 and any(r["cardId"] == C for r in exc), st)

print("== 3. liquidacion: posicion neta, cierre sellado, pago ==")
st, cycles = http("GET", "/clearing/settlement/cycles")
cyc = next(x for x in cycles if x["id"] == b["settlementCycleId"])
expected_net = 150 - 60 - 80 - float(b["feesAmount"])
check("ciclo VISA de hoy abierto con la posicion neta = presentaciones - reversos - contracargos - intercambio", cyc["status"] == "OPEN" and abs(float(cyc["netPosition"]) - expected_net) < 0.01 and cyc["direction"] == "ISSUER_RECEIVES", (cyc, expected_net))
st, closed = http("POST", f"/clearing/settlement/cycles/{cyc['id']}/close", {"by": "tesoreria"})
check("cierre: CLOSED, sellado, quien y cuando", st == 200 and closed["status"] == "CLOSED" and len(closed["sha256"]) == 64 and closed["closedBy"] == "tesoreria", (st, closed))
st, sf, hdr = http("GET", f"/clearing/settlement/cycles/{cyc['id']}/file", raw=True)
text = sf.decode("utf-8")
check("archivo de liquidacion CSV con lote, total y neto, y su sello coincide", st == 200 and text.startswith("network,cycle_date,batch_id") and ",NET," in text and "ISSUER RECEIVES" in text and hashlib.sha256(sf).hexdigest() == closed["sha256"], text[:200])
st, again = http("POST", f"/clearing/settlement/cycles/{cyc['id']}/close", {"by": "x"}); check("cerrar dos veces -> 409", st == 409, st)
st, late = http("POST", "/clearing/simulate", {"network": "VISA", "anomalies": "fee", "cardId": C, "ingest": True})
check("un archivo tardio para un ciclo cerrado -> 409 (necesita nuevo ciclo)", st == 409 and late.get("errorCode") == "SETTLEMENT_CYCLE_CLOSED", (st, late))
st, nopay = http("POST", f"/clearing/settlement/cycles/{cyc['id']}/paid", {"paymentRef": "", "by": "t"}); check("pagar sin referencia -> 400", st == 400, st)
st, paid = http("POST", f"/clearing/settlement/cycles/{cyc['id']}/paid", {"paymentRef": "SPEI-" + RUN, "by": "tesoreria"})
check("pagado con referencia SPEI", st == 200 and paid["status"] == "PAID" and paid["paymentRef"] == "SPEI-" + RUN, (st, paid))
au = sql("select string_agg(action, ',' order by id) from audit_logs where entity_name='SettlementCycle' and entity_id='%d'" % cyc["id"])
check("auditoria del ciclo: cierre y pago", au == "SETTLEMENT_CYCLE_CLOSED,SETTLEMENT_CYCLE_PAID", au)

print("== 4. limpieza ==")
subprocess.run([PSQL, "-h", "localhost", "-U", "postgres", "-d", "cms_mexico", "-c", """
with c as (select %d as id),
 b as (select id from clearing_batches where id in (select batch_id from clearing_records where card_id in (select id from c))),
 d0 as (delete from clearing_records where batch_id in (select id from b)),
 d0b as (delete from clearing_batches where id in (select id from b)),
 d0c as (delete from settlement_cycles where id = %d),
 d1 as (delete from ledger_entries where ledger_account_id in (select id from ledger_accounts where card_id in (select id from c))),
 d2 as (delete from ledger_accounts where card_id in (select id from c)),
 d3 as (delete from card_controls where card_id in (select id from c)),
 d4 as (delete from authorization_attempts where card_id in (select id from c)),
 d5 as (delete from fraud_alerts where card_id in (select id from c)),
 d6 as (delete from step_up_challenges where card_id in (select id from c)),
 d7 as (delete from plastics where card_id in (select id from c)),
 d8 as (delete from dispute_events where dispute_id in (select id from disputes where card_id in (select id from c))),
 d8b as (delete from dispute_evidences where dispute_id in (select id from disputes where card_id in (select id from c))),
 d8c as (delete from disputes where card_id in (select id from c)),
 d9 as (delete from guild_alerts where card_id in (select id from c)),
 d10 as (delete from reconciliation_items where card_id in (select id from c)),
 d11 as (delete from authorization_holds where card_id in (select id from c))
delete from cards where id in (select id from c)""" % (C, cyc["id"])], capture_output=True, text=True, env=dict(os.environ, PGPASSWORD="alodiga.123"))
check("datos de prueba borrados", sql("select count(*) from cards where id=%d" % C) == "0", "")

print(f"\nRESULTADO: {ok} OK, {bad} FAIL")
raise SystemExit(1 if bad else 0)
