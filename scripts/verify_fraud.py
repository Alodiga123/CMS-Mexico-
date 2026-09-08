# -*- coding: utf-8 -*-
"""End-to-end check of the online fraud engine on prepaid cards (no core needed).
Needs the CMS started with fraud.step-up.expose-otp=true."""
import os, json, subprocess, time, urllib.request, urllib.error
RUN = str(int(time.time()))[-6:]

CMS = "http://localhost:8085/api"
PSQL = r"C:\Program Files\PostgreSQL\17\bin\psql.exe"


def http(m, p, b=None):
    r = urllib.request.Request(CMS + p, data=json.dumps(b).encode() if b is not None else None, method=m,
                               headers={"Content-Type": "application/json", "X-Api-Key": os.environ.get("CMS_API_KEY", "dev-api-key"), "Accept": "application/json"})
    try:
        with urllib.request.urlopen(r, timeout=60) as x:
            return x.status, json.loads(x.read() or b"null")
    except urllib.error.HTTPError as e:
        try:
            return e.code, json.loads(e.read() or b"null")
        except Exception:
            return e.code, None


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
sql("update card_products set daily_limit=NULL, weekly_limit=NULL, monthly_limit=NULL, country='MX' where id=%d" % ppre)
seq = [9020]


def new_card(deposit, name):
    seq[0] += 1
    st, c = http("POST", "/customers", {"fullName": name, "phoneNumber": "5550000009", "cardLast4": str(seq[0]), "initialDeposit": deposit})
    st, k = http("POST", "/cards/issue", {"customerId": c["id"], "productId": ppre, "embossedName": name.upper(), "cardCategory": "VIRTUAL", "last4": str(seq[0]), "initialDeposit": deposit})
    card = k["id"]; http("POST", f"/cards/{card}/status", {"status": "ACTIVE"})
    # the cards in this test are minutes old; push their creation back so NEW_CARD_HIGH does not colour every case
    sql("update cards set created_at = now() - interval '30 days' where id=%d" % card)
    return card


def auth(card, amount, merchant="M-OK-"+RUN, channel="POS", country=None, token=None):
    b = {"cardId": card, "amount": amount, "merchantName": "Tienda " + merchant, "merchantId": merchant, "channel": channel}
    if country: b["countryCode"] = country
    if token: b["stepUpToken"] = token
    return http("POST", "/authorization", b)


print("== 0. respuestas explicables ==")
A = new_card(500, "Prueba Fraude A")
st, r = auth(A, 20)
check("aprobada con riskScore 0 y mensaje al cliente", r.get("responseCode") == "00" and r.get("riskScore") == 0 and r.get("customerMessage") == "Operación aprobada", r)
http("POST", f"/authorization/{r.get('approvalCode')}/reverse")
B = new_card(5, "Prueba Fraude B")
st, r = auth(B, 100)
check("51 lleva causa tecnica y mensaje 'Fondos insuficientes'", r.get("responseCode") == "51" and r.get("customerMessage") == "Fondos insuficientes" and "available" in r.get("message", ""), r)

print("== 1. lista de bloqueo ==")
st, bl = http("POST", "/fraud/blocklist", {"type": "MERCHANT_ID", "value": "M-BAD-"+RUN, "reason": "comercio reportado", "by": "ana"})
check("comercio bloqueado (201)", st == 201 and bl.get("active") is True, (st, bl))
st, r = auth(A, 10, merchant="M-BAD-"+RUN)
check("59 con BLOCKLIST_MERCHANT y score 100, sin tocar fondos", r.get("responseCode") == "59" and r.get("riskScore") == 100 and "BLOCKLIST_MERCHANT" in r.get("riskReasons", []), r)
st, al = http("GET", "/fraud/alerts?status=OPEN")
check("alerta DECLINED abierta para M-BAD", any(a["type"] == "DECLINED" and a["merchantId"] == "M-BAD-"+RUN for a in al), al)
st, r = auth(A, 10, merchant="M-BAD-"+RUN, country="US")
check("pais bloqueable tambien: aun 59 (comercio)", r.get("responseCode") == "59", r)

print("== 2. card testing -> verificacion reforzada en e-commerce ==")
B = new_card(5, "Prueba Fraude B2")  # fresca: la memoria del motor cuenta cada declinacion previa
for i in range(3):
    st, r = auth(B, 100, merchant="M-SHOP-"+RUN)
check("tres declinaciones por fondos (51)", r.get("responseCode") == "51", r)
st, r = auth(B, 3, merchant="M-SHOP-"+RUN, channel="ECOMMERCE")
check("4o intento en e-commerce -> 1A con reto y score 50 (DECLINES_30M)", r.get("responseCode") == "1A" and r.get("challengeId") and r.get("riskScore") == 50 and "DECLINES_30M" in r.get("riskReasons", []), r)
check("mensaje al cliente pide confirmar con el codigo", "código" in r.get("customerMessage", ""), r.get("customerMessage"))
tok, otp = r.get("challengeId"), r.get("otpHint")
check("el OTP viene expuesto solo porque expose-otp=true", otp and len(otp) == 6, otp)
st, v = http("POST", f"/fraud/challenges/{tok}/verify", {"otp": "000000"})
check("codigo equivocado -> 422 CHALLENGE_WRONG_CODE", st == 422 and v.get("errorCode") == "CHALLENGE_WRONG_CODE", (st, v))
st, v = http("POST", f"/fraud/challenges/{tok}/verify", {"otp": otp})
check("codigo correcto -> VERIFIED", st == 200 and v.get("status") == "VERIFIED", (st, v))
st, r = auth(B, 3, merchant="M-SHOP-"+RUN, channel="ECOMMERCE", token=tok)
check("reintento con el token -> 00 con STEP_UP_VERIFIED", r.get("responseCode") == "00" and "STEP_UP_VERIFIED" in r.get("riskReasons", []), r)
approved_after_stepup = r.get("approvalCode")
st, r = auth(B, 3, merchant="M-SHOP-"+RUN, channel="ECOMMERCE", token=tok)
check("el mismo token no vale dos veces -> 59 STEP_UP_INVALID", r.get("responseCode") == "59" and "STEP_UP_INVALID" in r.get("riskReasons", []), r)
st, ch = http("GET", f"/fraud/challenges/{tok}")
check("el reto queda CONSUMED", ch.get("status") == "CONSUMED", ch)

print("== 3. la misma senal en POS no puede retar: declina ==")
Cc = new_card(5, "Prueba Fraude C")
for i in range(3):
    auth(Cc, 100, merchant="M-SHOP-"+RUN)
st, r = auth(Cc, 3, merchant="M-SHOP-"+RUN, channel="POS")
check("POS con score de revision -> 59 STEP_UP_UNAVAILABLE_POS", r.get("responseCode") == "59" and "STEP_UP_UNAVAILABLE_POS" in r.get("riskReasons", []), r)

print("== 4. enumeracion por comercio ==")
for i in range(4):
    bad_card = new_card(1, "Prueba Enum %d" % i)
    http("POST", f"/cards/{bad_card}/status", {"status": "BLOCKED"})
    st, r = auth(bad_card, 1, merchant="M-ENUM-"+RUN, channel="ECOMMERCE")
check("cuatro tarjetas distintas bloqueadas fallan en M-ENUM (62)", r.get("responseCode") == "62", r)
st, r = auth(A, 15, merchant="M-ENUM-"+RUN, channel="ECOMMERCE")
check("una tarjeta sana en M-ENUM -> 1A con ENUMERATION (score 60)", r.get("responseCode") == "1A" and "ENUMERATION" in r.get("riskReasons", []) and r.get("riskScore") == 60, r)
st, al = http("GET", "/fraud/alerts?status=OPEN")
enum_alerts = [a for a in al if a["type"] == "ENUMERATION" and a["merchantId"] == "M-ENUM-"+RUN]
check("una sola alerta ENUMERATION para M-ENUM", len(enum_alerts) == 1, len(enum_alerts))

print("== 5. el analista bloquea el comercio desde la alerta ==")
st, rv = http("POST", f"/fraud/alerts/{enum_alerts[0]['id']}/review", {"action": "BLOCK_MERCHANT", "note": "enumeracion confirmada", "by": "ana"})
check("alerta REVIEWED con BLOCK_MERCHANT", st == 200 and rv.get("status") == "REVIEWED" and rv.get("actionTaken") == "BLOCK_MERCHANT", (st, rv))
st, bl = http("GET", "/fraud/blocklist")
check("M-ENUM ya esta en la lista de bloqueo", any(b["value"] == "M-ENUM-"+RUN and b["type"] == "MERCHANT_ID" for b in bl), bl)
st, r = auth(A, 15, merchant="M-ENUM-"+RUN, channel="ECOMMERCE")
check("siguiente intento en M-ENUM -> 59 BLOCKLIST_MERCHANT", r.get("responseCode") == "59" and "BLOCKLIST_MERCHANT" in r.get("riskReasons", []), r)

print("== 6. salto geografico y anomalia de importe quedan en el historial ==")
D = new_card(500, "Prueba Fraude D")
st, r = auth(D, 10, merchant="M-OK-"+RUN, country="MX")
check("compra domestica aprobada", r.get("responseCode") == "00", r)
st, r = auth(D, 12, merchant="M-OK-"+RUN, country="US")
check("minutos despues en US: aprobada pero con GEO_JUMP anotado (30 < 50)", r.get("responseCode") == "00" and "GEO_JUMP" in r.get("riskReasons", []) and r.get("riskScore") == 30, r)

print("== 7. historial de decisiones de la tarjeta B ==")
st, h = http("GET", f"/fraud/attempts?cardId={B}")
codes = [x["responseCode"] for x in h]
check("contiene 51, 1A, 00 y 59 con sus razones", st == 200 and {"51", "1A", "00", "59"} <= set(codes) and any("DECLINES_30M" in x["riskReasons"] for x in h), codes)
check("el aprobado tras el reto esta ligado a su approvalCode", any(x.get("approvalCode") == approved_after_stepup for x in h), approved_after_stepup)

print(f"\nRESULTADO: {ok} OK, {bad} FAIL")
raise SystemExit(1 if bad else 0)
