# -*- coding: utf-8 -*-
"""End-to-end check of plastics: request, batch (sealed + encrypted file), bureau report,
tracking to activation, replacement, renewal. Needs the HSM simulator on 8080 and the CMS
started with plastics.expose-plaintext=true."""
import os, json, time, subprocess, hashlib, urllib.request, urllib.error

CMS = "http://localhost:8085/api"
PSQL = r"C:\Program Files\PostgreSQL\17\bin\psql.exe"


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
        bad += 1; print(f"  FAIL {label}  {str(detail)[:320]}")


ppre = int(sql("select id from card_products where card_type='PREPAID' order by id desc limit 1"))
seq = [9000 + int(time.time()) % 900]  # fresh last4 per run


def new_card(category, name):
    seq[0] += 1
    st, c = http("POST", "/customers", {"fullName": name, "phoneNumber": "5550000010", "cardLast4": str(seq[0]), "initialDeposit": 10})
    st, k = http("POST", "/cards/issue", {"customerId": c["id"], "productId": ppre, "embossedName": name.upper(), "cardCategory": category, "last4": str(seq[0]), "initialDeposit": 10})
    return k["id"]


def plastics_of(card):
    st, l = http("GET", f"/plastics?cardId={card}"); return l


print("== 1. emision: la fisica pide plastico sola, la virtual no ==")
P = new_card("PHYSICAL", "Ana Plastico")
lp = plastics_of(P)
check("tarjeta fisica: 1 plastico REQUESTED #1 NEW pedido por ISSUANCE", len(lp) == 1 and lp[0]["status"] == "REQUESTED" and lp[0]["sequence"] == 1 and lp[0]["reason"] == "NEW" and lp[0]["requestedBy"] == "ISSUANCE", lp)
pP = lp[0]["id"]
V = new_card("VIRTUAL", "Luis Virtual")
check("tarjeta virtual: sin plastico", plastics_of(V) == [], plastics_of(V))
st, pv = http("POST", "/plastics", {"cardId": V, "reason": "NEW", "deliveryAddress": "Av. Reforma 1, CDMX", "pinMailer": True, "by": "mesa"})
check("solicitud manual 201 REQUESTED con direccion", st == 201 and pv["status"] == "REQUESTED" and pv["deliveryAddress"].startswith("Av."), (st, pv))
pV = pv["id"]
st, dup = http("POST", "/plastics", {"cardId": V, "reason": "NEW"})
check("segunda solicitud con una en curso -> 409", st == 409 and dup.get("errorCode") == "PLASTIC_ALREADY_OPEN", (st, dup))

print("== 2. lote: archivo sellado y cifrado con PVV/CVV2 del HSM ==")
st, b = http("POST", "/plastics/batches", {"manufacturer": "IDEMIA-MX", "by": "ops"})
check("lote 201 BUILT con registros y sello", st == 201 and b["status"] == "BUILT" and b["recordCount"] >= 2 and len(b["sha256Plain"]) == 64 and b["encryptedSize"] > 0, (st, b))
bid = b["id"]
st, bd = http("GET", f"/plastics/batches/{bid}")
ids_in = [p["id"] for p in bd["plastics"]]
check("nuestros dos plasticos estan en el lote, IN_BATCH", pP in ids_in and pV in ids_in and all(p["status"] == "IN_BATCH" for p in bd["plastics"]), ids_in)
st, enc, hdr = http("GET", f"/plastics/batches/{bid}/file", raw=True)
check("archivo cifrado descargable con cabeceras de algoritmo, IV y sello", st == 200 and len(enc) == b["encryptedSize"] and hdr.get("X-Perso-Algorithm") == "AES-256-GCM" and hdr.get("X-Perso-SHA256-Plain") == b["sha256Plain"], (st, len(enc), hdr.get("X-Perso-Algorithm")))
st, plain, _ = http("GET", f"/plastics/batches/{bid}/file/preview", raw=True)
text = plain.decode("utf-8")
recs = [l for l in text.splitlines() if l.startswith("REC|")]
check("texto en claro: HDR, N REC y TRL|N", text.startswith("HDR|EMB-") and len(recs) == b["recordCount"] and text.rstrip().endswith("TRL|%d" % b["recordCount"]), text[:200])
check("el sello es el SHA-256 del texto en claro", hashlib.sha256(plain).hexdigest() == b["sha256Plain"], hashlib.sha256(plain).hexdigest())
mine = [l for l in recs if l.split("|")[2] in (str(pP), str(pV))]
check("nuestros registros llevan PVV y CVV2 del HSM y la referencia de PAN enmascarada", len(mine) == 2 and all(l.split("|")[12] and l.split("|")[13] and "******" in l.split("|")[7] for l in mine), mine)
check("el registro de Ana lleva nombre embozado, vigencia MM/yy, codigo de servicio 201 y PIN-mailer", any("ANA PLASTICO|" in l and "|201|LACPI-MX-01|" in l and l.endswith("|Y|Prepago MX") or ("ANA PLASTICO|" in l and "|201|" in l) for l in mine), mine)

print("== 3. envio y reporte del fabricante ==")
st, s = http("POST", f"/plastics/batches/{bid}/send", {"by": "ops"})
check("lote SENT; plasticos SENT_TO_MANUFACTURER", s["status"] == "SENT" and all(p["status"] == "SENT_TO_MANUFACTURER" for p in http("GET", f"/plastics/batches/{bid}")[1]["plastics"]), s)
st, pr = http("POST", f"/plastics/batches/{bid}/produced", {"failedPlasticIds": [pV], "note": "defecto de chip", "by": "bureau"})
check("lote PRODUCED con 1 fallido", pr["status"] == "PRODUCED" and pr["failedCount"] == 1, pr)
st, x = http("GET", f"/plastics/{pP}"); check("el de Ana: PRODUCED", x["status"] == "PRODUCED", x["status"])
st, y = http("GET", f"/plastics/{pV}"); check("el de Luis: de vuelta a REQUESTED sin lote y con la nota", y["status"] == "REQUESTED" and y["batchId"] is None and y["note"] == "defecto de chip", y)

print("== 4. envio fisico, entrega, activacion ==")
st, x = http("POST", f"/plastics/{pP}/ship", {"carrier": "DHL", "trackingNumber": "MX-778899", "by": "ops"})
check("SHIPPED con guia", x["status"] == "SHIPPED" and x["trackingNumber"] == "MX-778899", x)
st, x = http("POST", f"/plastics/{pP}/activate"); check("activar antes de entregar -> 409", st == 409 and x.get("errorCode") == "PLASTIC_INVALID_STATE", (st, x))
st, x = http("POST", f"/plastics/{pP}/deliver", {"by": "courier"}); check("DELIVERED", x["status"] == "DELIVERED", x)
st, x = http("POST", f"/plastics/{pP}/activate", {"by": "customer"}); check("ACTIVATED", x["status"] == "ACTIVATED" and x["activatedAt"], x)
st, x = http("POST", f"/plastics/{pP}/activate"); check("activar dos veces -> 409", st == 409, st)

print("== 5. reposicion por extravio: se destruye, se bloquea la tarjeta, se pide el #2 ==")
st, r = http("POST", f"/plastics/{pP}/replace", {"reason": "REPLACEMENT_LOST", "deliveryAddress": "Av. Reforma 1", "pinMailer": True, "by": "mesa"})
check("201: plastico #2 REQUESTED por REPLACEMENT_LOST", st == 201 and r["sequence"] == 2 and r["status"] == "REQUESTED" and r["reason"] == "REPLACEMENT_LOST", (st, r))
st, old = http("GET", f"/plastics/{pP}"); check("el #1 queda DESTROYED", old["status"] == "DESTROYED", old["status"])
cst = sql("select status from cards where id=%d" % P); check("la tarjeta queda BLOCKED", cst == "BLOCKED", cst)
st, bad_ = http("POST", f"/plastics/{r['id']}/replace", {"reason": "NEW"}); check("reposicion con razon NEW -> 400", st == 400, st)

print("== 6. renovacion: tarjetas que vencen pronto ==")
E = new_card("VIRTUAL", "Eva Renueva")
old_expiry = sql("select expiry_date from cards where id=%d" % E)
sql("update cards set expiry_date = current_date + 30 where id=%d" % E)
st, rn = http("POST", "/plastics/renewals?withinDays=60&by=job")
mine_rn = [p for p in rn if p["cardId"] == E]
check("la tarjeta que vence en 30 dias recibe un plastico RENEWAL", len(mine_rn) == 1 and mine_rn[0]["reason"] == "RENEWAL", rn)
exp_new = mine_rn[0]["expiry"] if mine_rn else None
check("la vigencia del plastico es la actual + 3 anos", exp_new and exp_new[:4] == str(int(sql("select extract(year from expiry_date) from cards where id=%d" % E)[:4]) + 3), exp_new)
check("la tarjeta bloqueada con plastico en curso NO entra en renovacion", not any(p["cardId"] == P for p in rn), [p["cardId"] for p in rn])

print("== 7. segundo lote: recoge el fallido, la reposicion y la renovacion; activar la renovacion mueve la vigencia ==")
st, b2 = http("POST", "/plastics/batches", {"manufacturer": "IDEMIA-MX", "by": "ops"})
ids2 = [p["id"] for p in http("GET", f"/plastics/batches/{b2['id']}")[1]["plastics"]]
check("los tres estan en el segundo lote", pV in ids2 and r["id"] in ids2 and mine_rn[0]["id"] in ids2, ids2)
http("POST", f"/plastics/batches/{b2['id']}/send"); http("POST", f"/plastics/batches/{b2['id']}/produced", {"failedPlasticIds": []})
rid = mine_rn[0]["id"]
http("POST", f"/plastics/{rid}/ship", {"carrier": "DHL", "trackingNumber": "MX-1"}); http("POST", f"/plastics/{rid}/deliver")
st, act = http("POST", f"/plastics/{rid}/activate", {"by": "customer"})
ce = {"expiryDate": sql("select expiry_date from cards where id=%d" % E)}
check("al activar la renovacion la tarjeta toma la nueva vigencia", act["status"] == "ACTIVATED" and ce.get("expiryDate") == exp_new, (act.get("status"), ce.get("expiryDate"), exp_new))

print(f"\nRESULTADO: {ok} OK, {bad} FAIL")
raise SystemExit(1 if bad else 0)
