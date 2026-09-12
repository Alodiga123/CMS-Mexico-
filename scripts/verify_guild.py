# -*- coding: utf-8 -*-
"""End-to-end check of the industry antifraud connection (guild: SNA / SVL / SPC) against the
in-memory simulator: outbox with folio and retries, SVL hit declining in the authorizer with cache
and fail-open, inbound alerts blocking cards and listing merchants with deadlines, SPC notice on
chargeback, deadline expiry (assumed loss). Needs the CMS with guild.mode=simulated (default)."""
import os, json, time, subprocess, urllib.request, urllib.error
import sys as _sys; _sys.path.insert(0, __import__("os").path.dirname(__file__)); from kyc_demo import identity

CMS = "http://localhost:8085/api"
PSQL = r"C:\Program Files\PostgreSQL\17\bin\psql.exe"
RUN = str(int(time.time()) % 100000)


def http(m, p, b=None):
    r = urllib.request.Request(CMS + p, data=json.dumps(b).encode() if b is not None else None, method=m,
                               headers={"Content-Type": "application/json", "X-Api-Key": os.environ.get("CMS_API_KEY", "dev-api-key"), "Accept": "*/*"})
    try:
        with urllib.request.urlopen(r, timeout=90) as x:
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
        bad += 1; print(f"  FAIL {label}  {str(detail)[:360]}")


ppre = int(sql("select id from card_products where product_code='PRE-MX'") or sql("select id from card_products where card_type='PREPAID' order by id desc limit 1"))
BIN = sql("select bin from card_products where id=%d" % ppre)
seq = [7000 + int(time.time()) % 900]


def new_card(name, deposit=500):
    seq[0] += 1
    st, c = http("POST", "/customers", {**identity(name), "fullName": name, "phoneNumber": "5550000040", "cardLast4": str(seq[0]), "initialDeposit": 10})
    st, k = http("POST", "/cards/issue", {"customerId": c["id"], "productId": ppre, "embossedName": name.upper(), "cardCategory": "VIRTUAL", "last4": str(seq[0]), "initialDeposit": deposit})
    card = k["id"]; http("POST", f"/cards/{card}/status", {"status": "ACTIVE"})
    sql("update cards set created_at = now() - interval '30 days' where id=%d" % card)
    return card, str(seq[0])


def auth(card, amount, merchant, channel="POS"):
    return http("POST", "/authorization", {"cardId": card, "amount": amount, "merchantName": "Tienda " + merchant, "merchantId": merchant, "channel": channel})


print("== 0. estado ==")
st, s0 = http("GET", "/guild/status")
check("conexion en modo simulado, arriba, con plazos configurados", st == 200 and s0["mode"] == "simulated" and s0["up"] is True and s0["closeBusinessDays"] == 10 and s0["enumerationResponseDays"] == 5, s0)
http("POST", "/guild/simulator/down", {"down": False})

print("== 1. SNA saliente: enumeracion detectada por el motor -> outbox -> folio ==")
M = "M-ENUM-" + RUN
for i in range(4):
    bc, _ = new_card("Enum %d %s" % (i, RUN))
    http("POST", f"/cards/{bc}/status", {"status": "BLOCKED"})
    auth(bc, 1, M, "ECOMMERCE")
A, A4 = new_card("Ana Gremio " + RUN)
st, r = auth(A, 15, M, "ECOMMERCE")
check("el motor marca ENUMERATION en el comercio", "ENUMERATION" in r.get("riskReasons", []), r)
st, out = http("GET", "/guild/alerts?direction=OUTBOUND&status=PENDING_SEND")
mine = [a for a in out if a["merchantId"] == M]
check("una alerta saliente ENUMERATION en el outbox con plazo de 5 dias", len(mine) == 1 and mine[0]["type"] == "ENUMERATION" and mine[0]["respondBy"] == time.strftime("%Y-%m-%d", time.localtime(time.time() + 5 * 86400)), mine)
aid = mine[0]["id"]
st, fl = http("POST", "/guild/outbox/flush")
st, a1 = http("GET", f"/guild/alerts/{aid}")
check("el trabajo la envia y el gremio contesta con folio SNA-", fl["sent"] >= 1 and a1["status"] == "SENT" and a1["guildFolio"].startswith("SNA-"), (fl, a1))
st, sent = http("GET", "/guild/simulator/sent")
check("el gremio recibio tipo, comercio y referencia local", any(x["type"] == "ENUMERATION" and x["merchantId"] == M and x["localRef"] == "CMS-%d" % aid for x in sent), sent[-1:])
au = sql("select string_agg(action, ',' order by id) from audit_logs where entity_name='GuildAlert' and entity_id='%d'" % aid)
check("auditoria: encolada y enviada", au == "GUILD_ALERT_QUEUED,GUILD_ALERT_SENT", au)

print("== 2. caida del canal: FAILED con reintentos, reenvio manual ==")
http("POST", "/guild/simulator/down", {"down": True})
st, m1 = http("POST", "/guild/alerts", {"type": "CONFIRMED_FRAUD", "cardId": A, "description": "fraude confirmado por el titular", "amount": 120, "by": "ana"})
check("201: alerta manual de fraude confirmado con bin y last4 de la tarjeta", st == 201 and m1["status"] == "PENDING_SEND" and m1["bin"] == BIN and m1["last4"] == A4, (st, m1))
st, fl = http("POST", "/guild/outbox/flush")
st, m1b = http("GET", f"/guild/alerts/{m1['id']}")
check("con el gremio caido queda FAILED con el error y 1 intento", fl["sent"] == 0 and m1b["status"] == "FAILED" and m1b["attempts"] == 1 and "down" in (m1b["lastError"] or ""), (fl, m1b))
st, st0 = http("GET", "/guild/status"); check("el estado muestra el canal caido y la fallida", st0["up"] is False and st0["failed"] >= 1, st0)
http("POST", "/guild/simulator/down", {"down": False})
st, m1c = http("POST", f"/guild/alerts/{m1['id']}/send")
check("reenvio manual: SENT con folio", st == 200 and m1c["status"] == "SENT" and m1c["guildFolio"], (st, m1c))
st, badt = http("POST", "/guild/alerts", {"type": "SUSPICIOUS_MERCHANT", "description": "sin comercio"}); check("SUSPICIOUS_MERCHANT sin merchantId -> 400", st == 400 and badt.get("errorCode") == "GUILD_MERCHANT_REQUIRED", (st, badt))
st, badt = http("POST", "/guild/alerts", {"type": "NOPE"}); check("tipo desconocido -> 400", st == 400 and badt.get("errorCode") == "GUILD_BAD_TYPE", (st, badt))

print("== 3. SVL: la lista del gremio declina en el autorizador, con cache y fail-open ==")
B, B4 = new_card("Beto Listado " + RUN)
st, r = auth(B, 10, "M-OK-" + RUN)
check("antes de listarla: aprobada", r.get("approved") is True, r)
st, v = http("GET", "/guild/verifications")
vb = [x for x in v if x["subjectType"] == "CARD" and x["subject"] == str(B)]
check("la primera autorizacion consulto al gremio y guardo la respuesta (no listada, vigente 24h)", len(vb) == 1 and vb[0]["listed"] is False and vb[0]["degraded"] is False and vb[0]["validUntil"], vb)
http("POST", "/guild/simulator/listed-cards", {"bin": BIN, "last4": B4, "reason": "tarjeta en volcado de datos"})
st, r = auth(B, 10, "M-OK-" + RUN)
check("recien listada pero con cache vigente: sigue aprobada (no se pregunta dos veces)", r.get("approved") is True, r)
sql("update guild_verifications set valid_until = now() - interval '1 minute' where subject_type='CARD' and subject='%d'" % B)
st, r = auth(B, 10, "M-OK-" + RUN)
check("vencida la cache: el gremio la reporta y el autorizador declina 59 GUILD_SVL_LISTED", r.get("responseCode") == "59" and "GUILD_SVL_LISTED" in r.get("riskReasons", []), r)
st, bl = http("GET", "/fraud/blocklist")
hit = [x for x in bl if x["type"] == "CARD" and x["value"] == str(B)]
check("la tarjeta entro a la lista de bloqueo local con origen EXTERNAL y folio SVL", len(hit) == 1 and hit[0]["source"] == "EXTERNAL" and "GUILD SVL" in hit[0]["reason"], hit)
st, inb = http("GET", "/guild/alerts?direction=INBOUND")
ic = [x for x in inb if x["cardId"] == B and x["type"] == "COMPROMISED_CARD"]
check("y quedo una alerta entrante COMPROMISED_CARD en revision con plazo", len(ic) == 1 and ic[0]["status"] == "IN_REVIEW" and ic[0]["respondBy"] and ic[0]["autoAction"] == "CARD_BLOCKLISTED", ic)
st, r = auth(B, 10, "M-OK-" + RUN)
check("siguiente intento: declina por la lista local sin volver al gremio (BLOCKLIST_CARD)", r.get("responseCode") == "59" and "BLOCKLIST_CARD" in r.get("riskReasons", []), r)
# fail-open
C, C4 = new_card("Carla Degradada " + RUN)
http("POST", "/guild/simulator/down", {"down": True})
st, r = auth(C, 10, "M-OK-" + RUN)
st, v = http("GET", "/guild/verifications")
vc = [x for x in v if x["subjectType"] == "CARD" and x["subject"] == str(C)]
check("gremio caido: fail-open, aprobada, verificacion marcada como degradada", r.get("approved") is True and len(vc) == 1 and vc[0]["degraded"] is True, (r.get("responseCode"), vc))
http("POST", "/guild/simulator/down", {"down": False})
st, vm = http("POST", "/guild/verify/merchant/M-OK-" + RUN)
check("verificar comercio a peticion: no listado", st == 200 and vm["listed"] is False and vm["subjectType"] == "MERCHANT", (st, vm))

print("== 4. SNA entrante: tarjeta comprometida se bloquea, comercio se lista, plazo de 10 dias habiles ==")
D, D4 = new_card("Dora Entrante " + RUN)
st, i1 = http("POST", "/guild/simulator/inbound", {"type": "COMPROMISED_CARD", "bin": BIN, "last4": D4, "description": "reportada por otro emisor"})
st, i2 = http("POST", "/guild/simulator/inbound", {"type": "SUSPICIOUS_MERCHANT", "merchantId": "M-BAD-" + RUN, "merchantName": "Tienda Mala", "description": "muchos contracargos"})
st, pl = http("POST", "/guild/inbound/poll")
check("el sondeo trae las dos alertas", pl["received"] == 2, pl)
st, pl2 = http("POST", "/guild/inbound/poll"); check("un segundo sondeo no las duplica", pl2["received"] == 0, pl2)
st, inb = http("GET", "/guild/alerts?direction=INBOUND&status=IN_REVIEW")
d1 = [x for x in inb if x["guildFolio"] == i1["folio"]]; d2 = [x for x in inb if x["guildFolio"] == i2["folio"]]
check("alerta de tarjeta: guardada con folio del gremio, tarjeta resuelta por bin+last4 y bloqueada", len(d1) == 1 and d1[0]["cardId"] == D and d1[0]["autoAction"] == "CARD_BLOCKED", d1)
check("la tarjeta quedo BLOCKED en el CMS", sql("select status from cards where id=%d" % D) == "BLOCKED", sql("select status from cards where id=%d" % D))
st, r = auth(D, 5, "M-OK-" + RUN); check("y ya no autoriza", r.get("approved") is False, r.get("responseCode"))
check("alerta de comercio: MERCHANT_BLOCKLISTED", len(d2) == 1 and d2[0]["autoAction"] == "MERCHANT_BLOCKLISTED", d2)
st, r = auth(A, 5, "M-BAD-" + RUN); check("una compra en ese comercio declina 59 BLOCKLIST_MERCHANT", r.get("responseCode") == "59" and "BLOCKLIST_MERCHANT" in r.get("riskReasons", []), r)
import datetime
def business_days(n):
    d = datetime.date.today(); k = 0
    while k < n:
        d += datetime.timedelta(days=1)
        if d.weekday() < 5: k += 1
    return d.isoformat()
check("plazo de respuesta = hoy + 10 dias habiles", d1[0]["respondBy"] == business_days(10), (d1[0]["respondBy"], business_days(10)))
au = sql("select count(*) from audit_logs where action='BLOCK_CARD_GUILD_ALERT' and entity_id='%d'" % D)
check("bloqueo auditado como originado por el gremio", au == "1", au)

print("== 5. cierre y vencimiento (quebranto asumido) ==")
st, cl = http("POST", f"/guild/alerts/{d2[0]['id']}/close", {"resolution": "comercio confirmado con el adquirente", "by": "ana"})
check("la alerta de comercio se cierra con resolucion y quien", st == 200 and cl["status"] == "CLOSED" and cl["closedBy"] == "ana", (st, cl))
st, cl2 = http("POST", f"/guild/alerts/{d2[0]['id']}/close", {"resolution": "otra vez"}); check("cerrar dos veces -> 409", st == 409 and cl2.get("errorCode") == "GUILD_ALERT_CLOSED", (st, cl2))
sql("update guild_alerts set respond_by = current_date - 1 where id=%d" % d1[0]["id"])
st, ex = http("POST", "/guild/deadlines/run")
st, d1b = http("GET", f"/guild/alerts/{d1[0]['id']}")
check("vencido el plazo: EXPIRED con quebranto asumido", ex["expired"] >= 1 and d1b["status"] == "EXPIRED" and d1b["assumedLoss"] is True, (ex, d1b))
st, s1 = http("GET", "/guild/status"); check("el estado cuenta las vencidas", s1["expired"] >= 1, s1)

print("== 6. SPC: el contracargo avisa al gremio ==")
E, E4 = new_card("Eli Contracargo " + RUN, deposit=300)
st, r = auth(E, 80, "M-SHOP-" + RUN); code = r["approvalCode"]
http("POST", f"/authorization/{code}/capture")
st, d = http("POST", "/disputes", {"cardId": E, "approvalCode": code, "reasonCode": "13.1", "description": "no llego", "provisionalCredit": False, "openedBy": "mesa"})
http("POST", f"/disputes/{d['id']}/evidence", {"filename": "guia.txt", "contentType": "text/plain", "text": "guia 123", "description": "guia", "by": "mesa"})
st, cb = http("POST", f"/disputes/{d['id']}/chargeback", {"acquirerCaseRef": "ACQ-" + RUN, "by": "mesa"})
check("contracargo enviado", st == 200 and cb["status"] == "CHARGEBACK_SENT", (st, cb))
st, out = http("GET", "/guild/alerts?direction=OUTBOUND")
spc = [x for x in out if x["sourceRef"] == "DISPUTE:%d" % d["id"]]
check("alerta CHARGEBACK_PREVENTION en el outbox con tarjeta, monto y razon", len(spc) == 1 and spc[0]["type"] == "CHARGEBACK_PREVENTION" and spc[0]["cardId"] == E and float(spc[0]["amount"]) == 80 and "13.1" in spc[0]["description"], spc)
http("POST", "/guild/outbox/flush")
st, sent = http("GET", "/guild/simulator/sent")
check("el gremio la recibio por la ruta de prevencion de contracargos", any(x["type"] == "CHARGEBACK_PREVENTION" and x["externalRef"] == "DISPUTE:%d" % d["id"] for x in sent), sent[-1:])

print(f"\nRESULTADO: {ok} OK, {bad} FAIL")
raise SystemExit(1 if bad else 0)
