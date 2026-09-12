# -*- coding: utf-8 -*-
"""End-to-end check of the reports: catalogue, the six reports fed by fresh activity,
sealed runs (CSV + SHA-256), download, verification and tamper detection.
Also GET /api/cards/{id} and the audited status change."""
import os, json, time, subprocess, hashlib, urllib.request, urllib.error
import sys as _sys; _sys.path.insert(0, __import__("os").path.dirname(__file__)); from kyc_demo import identity

CMS = "http://localhost:8085/api"
PSQL = r"C:\Program Files\PostgreSQL\17\bin\psql.exe"
RUN = str(int(time.time()) % 100000)


def http(m, p, b=None, raw=False):
    r = urllib.request.Request(CMS + p, data=json.dumps(b).encode() if b is not None else None, method=m,
                               headers={"Content-Type": "application/json", "X-Api-Key": os.environ.get("CMS_API_KEY", "dev-api-key"), "Accept": "*/*"})
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


def row(table, **match):
    """First row of a report table whose named columns equal the given values."""
    cols = table["columns"]
    for r in table["rows"]:
        d = dict(zip(cols, r))
        if all(d.get(k) == v for k, v in match.items()): return d
    return None


ppre = int(sql("select id from card_products where card_type='PREPAID' order by id desc limit 1"))
pcode = sql("select product_code from card_products where id=%d" % ppre)
seq = [8000 + int(time.time()) % 900]


def new_card(name, deposit=5000, category="VIRTUAL"):
    seq[0] += 1
    st, c = http("POST", "/customers", {**identity(name), "fullName": name, "phoneNumber": "5550000020", "cardLast4": str(seq[0]), "initialDeposit": 10})
    st, k = http("POST", "/cards/issue", {"customerId": c["id"], "productId": ppre, "embossedName": name.upper(), "cardCategory": category, "last4": str(seq[0]), "initialDeposit": deposit})
    return k["id"]


def auth(card, amount, merchant):
    return http("POST", "/authorization", {"cardId": card, "amount": amount, "merchantName": "Tienda " + merchant, "merchantId": merchant, "channel": "POS"})


print("== 1. catalogo ==")
st, cat = http("GET", "/reports")
codes = [d["code"] for d in cat]
check("seis reportes de la maqueta", st == 200 and codes == ["DAILY_ISSUANCE", "PROCESSOR_RECONCILIATION", "R24_BANXICO", "PLD_UIF", "DISPUTES_ACTIVITY", "INACTIVE_CARDS"], codes)
check("los regulatorios llevan regulador y periodicidad", all(d["regulator"] and d["frequency"] for d in cat if d["tag"] == "Regulatorio"), cat)
st, nf = http("GET", "/reports/NOPE"); check("reporte desconocido -> 404", st == 404 and nf.get("errorCode") == "REPORT_NOT_FOUND", (st, nf))
st, bp = http("GET", "/reports/PLD_UIF?from=2026-09-10&to=2026-09-01"); check("periodo invertido -> 400", st == 400 and bp.get("errorCode") == "REPORT_BAD_PERIOD", (st, bp))

print("== 2. actividad fresca: tarjeta, autorizaciones, captura parcial, aclaracion, cambio de estado ==")
C = new_card("Rosa Reporte")
st, a1 = auth(C, 30, "M-A-" + RUN); st, a2 = auth(C, 60, "M-B-" + RUN); st, a3 = auth(C, 999999, "M-C-" + RUN)
check("dos aprobadas y una rechazada", a1["approved"] and a2["approved"] and not a3["approved"], (a1.get("responseCode"), a2.get("responseCode"), a3.get("responseCode")))
code1 = a1["approvalCode"]
st, cap = http("POST", f"/authorization/{code1}/capture", {"amount": 25})
check("captura parcial 25 de 30", st == 200 and cap["status"] == "CAPTURED" and float(cap["capturedAmount"]) == 25, (st, cap))
st, d = http("POST", "/disputes", {"cardId": C, "approvalCode": code1, "reasonCode": "13.1", "description": "reporte e2e", "provisionalCredit": False, "openedBy": "mesa"})
check("aclaracion abierta sobre la captura", st == 201 and d["status"] == "OPENED", (st, d))
st, sb = http("POST", f"/cards/{C}/status", {"status": "BLOCKED", "by": "mesa.control"})
st, one = http("GET", f"/cards/{C}")
check("GET /cards/{id}: la tarjeta bloqueada, misma forma que la lista", st == 200 and one["id"] == C and one["status"] == "BLOCKED" and one["last4"] == str(seq[0]) and "balance" in one, (st, one))
st, sa = http("POST", f"/cards/{C}/status", {"status": "ACTIVE", "by": "mesa.control"})
st, none = http("GET", "/cards/999999999"); check("tarjeta inexistente -> 404", st == 404 and none.get("errorCode") == "CARD_NOT_FOUND", (st, none))
au = sql("select string_agg(action||':'||username, ',' order by id) from audit_logs where entity_name='Card' and entity_id='%d' and action like 'CARD_STATUS_%%'" % C)
check("el cambio de estado queda en auditoria con quien lo pidio", au == "CARD_STATUS_BLOCKED:mesa.control,CARD_STATUS_ACTIVE:mesa.control", au)
OLD = new_card("Ines Inactiva", deposit=100)
sql("update cards set created_at = current_date - 120 where id=%d" % OLD)

print("== 3. los seis reportes en vivo ==")
st, di = http("GET", "/reports/DAILY_ISSUANCE")
today = row(di, fecha=str(time.strftime("%Y-%m-%d")))
check("corte diario: hoy con emitidas, bloqueadas, activadas, aprobadas y rechazadas", st == 200 and today and today["emitidas"] >= 1 and today["bloqueadas"] >= 1 and today["activadas"] >= 1 and today["aut_aprobadas"] >= 2 and today["aut_rechazadas"] >= 1 and float(today["monto_aprobado"]) >= 90, today)
check("corte diario: totales y tarjetas por estado", di["summary"]["emitidas"] >= 1 and "ACTIVE" in di["summary"]["tarjetasPorEstado"], di["summary"])

st, pr = http("GET", "/reports/PROCESSOR_RECONCILIATION")
r1 = row(pr, autorizacion=code1); r2 = row(pr, autorizacion=a2["approvalCode"])
check("conciliacion: la captura parcial muestra 30 autorizado, 25 compensado, diferencia 5", r1 and r1["estado"] == "CAPTURED" and float(r1["autorizado"]) == 30 and float(r1["compensado"]) == 25 and float(r1["diferencia"]) == 5, r1)
check("conciliacion: la retencion viva sigue HELD y cuenta como pendiente", r2 and r2["estado"] == "HELD" and pr["summary"]["pendientesDeCompensar"] >= 1 and pr["summary"]["compensacionesParciales"] >= 1 and "diferenciasAbiertasConCore" in pr["summary"], (r2, pr["summary"]))

st, r24 = http("GET", "/reports/R24_BANXICO")
prow = row(r24, producto=pcode)
check("R24: el producto prepago con emitidas del periodo, vigentes y operaciones por canal", prow and prow["emitidas_periodo"] >= 2 and prow["vigentes"] >= 1 and prow["operaciones"] >= 2 and prow["ops_pos"] >= 2 and float(prow["monto"]) >= 90, prow)
check("R24: una columna por canal", all(c in r24["columns"] for c in ["ops_pos", "ops_atm", "ops_ecommerce", "ops_contactless"]), r24["columns"])

st, pld = http("GET", "/reports/PLD_UIF?threshold=50")
rel = row(pld, tipo="RELEVANTE", tarjeta_id=C)
check("PLD: la operacion de 60 es relevante con umbral 50; la de 30 no", rel and float(rel["monto"]) == 60 and not row(pld, tipo="RELEVANTE", referencia=code1), (rel, pld["summary"]))
check("PLD: parametros y resumen por tipo", pld["params"]["umbralRelevante"] == 50 and all(k in pld["summary"] for k in ["relevante", "acumulada", "inusual", "montoRelevante"]), (pld["params"], pld["summary"]))
st, pld_hi = http("GET", "/reports/PLD_UIF")
check("PLD: con el umbral por defecto (10000) nuestra tarjeta no es relevante", not row(pld_hi, tipo="RELEVANTE", tarjeta_id=C), pld_hi["params"])

st, da = http("GET", "/reports/DISPUTES_ACTIVITY")
drow = row(da, razon="13.1", estado="OPENED")
check("aclaraciones: 13.1 OPENED con casos, monto y antiguedad 0-15", drow and drow["casos"] >= 1 and float(drow["monto"]) >= 25 and drow["dias_0_15"] >= 1, drow)
check("aclaraciones: resumen con abiertas", da["summary"]["abiertas"] >= 1, da["summary"])

st, ina = http("GET", "/reports/INACTIVE_CARDS")
irow = row(ina, tarjeta_id=OLD)
check("inactivas: la tarjeta de hace 120 dias sin uso aparece; la de hoy no", irow and irow["dias_inactiva"] >= 120 and irow["ultima_operacion"] is None and not row(ina, tarjeta_id=C), (irow, ina["params"]))
check("inactivas: resumen", ina["params"]["dias"] == 90 and ina["summary"]["inactivas"] >= 1 and ina["summary"]["nuncaUsadas"] >= 1, ina["summary"])
st, ina200 = http("GET", "/reports/INACTIVE_CARDS?days=200"); check("inactivas con 200 dias: ya no aparece", not row(ina200, tarjeta_id=OLD), ina200["params"])

print("== 4. corridas selladas: CSV, sello, descarga, verificacion, auditoria ==")
st, run = http("POST", "/reports/PLD_UIF/runs?threshold=50", {"by": "auditoria"})
check("201: corrida con id, sello y filas", st == 201 and run["id"] and len(run["sha256"]) == 64 and run["rowCount"] >= 1 and run["generatedBy"] == "auditoria" and "umbralRelevante=50" in run["params"], (st, run))
rid = run["id"]
st, body, hdr = http("GET", f"/reports/runs/{rid}/csv", raw=True)
check("descarga CSV con el sello en cabecera y nombre de archivo", st == 200 and hdr.get("X-Report-SHA256") == run["sha256"] and "pld_uif_" in hdr.get("Content-Disposition", ""), (st, hdr))
check("el sello es el SHA-256 del archivo descargado", hashlib.sha256(body).hexdigest() == run["sha256"], hashlib.sha256(body).hexdigest())
text = body.decode("utf-8")
HEADER = "tipo,fecha,tarjeta_id,last4,cliente,monto,comercio,pais,canal,motivo,referencia"
check("el CSV lleva cabecera y nuestra fila relevante", text.startswith(HEADER) and "RELEVANTE" in text and (",%d," % C) in text, text[:300])
st, v = http("GET", f"/reports/runs/{rid}/verify"); check("verificacion: valido", v.get("valid") is True and v["sha256"] == run["sha256"], v)
st, lst = http("GET", "/reports/runs?code=PLD_UIF"); check("la corrida aparece en el historial por codigo", any(r["id"] == rid for r in lst), [r["id"] for r in lst][:5])
st, meta = http("GET", f"/reports/runs/{rid}"); check("metadatos de la corrida sin el archivo", meta["id"] == rid and "csv" not in meta and meta["size"] == len(body), meta)
au = sql("select username from audit_logs where action='GENERATE_REPORT_PLD_UIF' and entity_id='%d'" % rid)
check("la generacion queda auditada", au == "auditoria", au)

st, run2 = http("POST", "/reports/DAILY_ISSUANCE/runs", {"by": "ops"})
sql("update report_runs set csv = csv || 'x' where id=%d" % run2["id"])
st, v2 = http("GET", f"/reports/runs/{run2['id']}/verify"); check("un archivo alterado en la base ya no verifica", v2.get("valid") is False, v2)
sql("delete from report_runs where id=%d" % run2["id"])
st, gone = http("GET", f"/reports/runs/{run2['id']}"); check("corrida inexistente -> 404", st == 404 and gone.get("errorCode") == "REPORT_RUN_NOT_FOUND", (st, gone))

print(f"\nRESULTADO: {ok} OK, {bad} FAIL")
raise SystemExit(1 if bad else 0)
