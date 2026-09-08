# -*- coding: utf-8 -*-
"""ISO 8583 channel, cryptographic validation and stand-in, end to end.
Plays the switch: opens the TCP link to the CMS (port 8583) and sends 0800, 0100 / 0200, 0400, 0120.
Plays the terminal and the chip through the HSM simulator's lab endpoints (PIN block under the
zone key, ARQC with the issuer key). Needs the CMS started with HSM_EXPOSE_TEST_SECRETS=true and
CORE_ALLOW_SIMULATED_OUTAGE=true, the simulator on 1500/8080 and the system API key."""
import os, json, time, socket, struct, subprocess, urllib.request, urllib.error

CMS = "http://localhost:8085/api"
HSM = "http://localhost:8080"
ISO_HOST, ISO_PORT = "localhost", 8583
PSQL = r"C:\Program Files\PostgreSQL\17\bin\psql.exe"
API_KEY = os.environ.get("CMS_API_KEY", "dev-api-key")
RUN = str(int(time.time()) % 100000)
KEYS = json.load(open(os.path.join(os.path.dirname(os.path.abspath(__file__)), "hsm_keys.json"))) if os.path.exists(os.path.join(os.path.dirname(os.path.abspath(__file__)), "hsm_keys.json")) else None


def http(m, p, b=None, base=CMS):
    h = {"Content-Type": "application/json", "Accept": "application/json", "X-Api-Key": API_KEY}
    r = urllib.request.Request(base + p, data=json.dumps(b).encode() if b is not None else None, method=m, headers=h)
    try:
        with urllib.request.urlopen(r, timeout=60) as x: return x.status, json.loads(x.read() or b"null")
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


# ---------------------------------------------------------------- ISO 8583 client (ASCII, hex bitmap, 2-byte length)
FIXED = {3: 6, 4: 12, 7: 10, 11: 6, 12: 6, 13: 4, 14: 4, 18: 4, 19: 3, 22: 3, 23: 3, 25: 2, 37: 12, 38: 6, 39: 2, 41: 8, 42: 15, 43: 40, 49: 3, 52: 16, 70: 3, 90: 42, 128: 16}
VAR = {2: 2, 32: 2, 35: 2, 45: 3, 48: 3, 54: 3, 55: 3, 102: 2, 103: 2}


def encode(mti, fields):
    keys = sorted(fields)
    bits = [0, 0]
    if any(k > 64 for k in keys): bits[0] |= 1 << 63
    for k in keys:
        idx, bit = (k - 1) // 64, 63 - ((k - 1) % 64)
        bits[idx] |= 1 << bit
    out = mti + "%016X" % bits[0] + ("%016X" % bits[1] if bits[0] & (1 << 63) else "")
    for k in keys:
        v = str(fields[k])
        if k in FIXED:
            assert len(v) == FIXED[k], (k, v)
            out += v
        else:
            out += ("%0" + str(VAR[k]) + "d") % len(v) + v
    return out.encode("ascii")


def decode(data):
    s = data.decode("ascii"); pos = 0
    mti = s[:4]; pos = 4
    primary = int(s[pos:pos + 16], 16); pos += 16
    secondary = 0
    if primary & (1 << 63): secondary = int(s[pos:pos + 16], 16); pos += 16
    fields = {}
    for f in range(2, 129):
        present = (primary & (1 << (64 - f))) if f <= 64 else (secondary & (1 << (128 - f)))
        if not present: continue
        if f in FIXED: ln = FIXED[f]
        else: ln = int(s[pos:pos + VAR[f]]); pos += VAR[f]
        fields[f] = s[pos:pos + ln]; pos += ln
    return mti, fields


class Link:
    def __init__(self):
        self.s = socket.create_connection((ISO_HOST, ISO_PORT), timeout=30)
    def send(self, mti, fields):
        body = encode(mti, fields)
        self.s.sendall(struct.pack(">H", len(body)) + body)
        hdr = self.s.recv(2)
        n = struct.unpack(">H", hdr)[0]
        buf = b""
        while len(buf) < n: buf += self.s.recv(n - len(buf))
        return decode(buf)
    def close(self): self.s.close()


stan = [int(RUN[-4:]) * 10 % 999000 + 1000]
def base(pan, amount_cents, extra=None, pc="000000", entry="051", merchant="MERCH" + RUN):
    stan[0] += 1
    f = {2: pan, 3: pc, 4: "%012d" % amount_cents, 7: time.strftime("%m%d%H%M%S"), 11: "%06d" % stan[0], 12: time.strftime("%H%M%S"), 13: time.strftime("%m%d"),
         18: "5411", 22: entry, 25: "00", 32: "12345", 37: ("%012d" % (int(RUN) * 100 + stan[0] % 100))[-12:], 41: "TERM0001", 42: (merchant + " " * 15)[:15],
         43: ("Tienda ISO " + RUN + " CDMX").ljust(37)[:37] + "MEX", 49: "484"}
    if extra: f.update(extra)
    return f


def lab(p, b):
    st, r = http("POST", p, b, base=HSM); return r


def tlv(tag, hexval): return tag + "%02X" % (len(hexval) // 2) + hexval


# ---------------------------------------------------------------- setup
http("POST", "/core/outage", {"down": False})  # a previous run may have left the breaker pulled
st, iso = http("GET", "/iso/status")
check("canal ISO escuchando y HSM arriba", st == 200 and iso["listening"] and iso["hsmUp"], iso)
ppre = int(sql("select id from card_products where product_code='PRE-MX'"))
pdeb = int(sql("select id from card_products where product_code='DEB-CORE-MX'"))


def new_card(product, name, deposit=500, external=None):
    st, c = http("POST", "/customers", {"fullName": name, "phoneNumber": "5550000060", "cardLast4": "0000", "initialDeposit": 10})
    body = {"customerId": c["id"], "productId": product, "embossedName": name.upper(), "cardCategory": "VIRTUAL", "initialDeposit": deposit}
    if external: body["externalAccountId"] = external
    st, k = http("POST", "/cards/issue", body)
    assert st in (200, 201), (st, k)
    sql("update cards set created_at = now() - interval '30 days' where id=%d" % k["id"])
    st, sec = http("GET", "/cards/%d/test-secrets" % k["id"])
    assert st == 200, (st, sec)
    return k["id"], sec


C, S = new_card(ppre, "Iso Prepago " + RUN)
C2, S2 = new_card(ppre, "Iso Negativa " + RUN)   # the card that takes the wrong PINs and CVVs, so its declines never colour the approvals
check("tarjeta emitida con PAN en boveda, PVV del HSM, PIN de prueba y CVVs", len(S["pan"]) == 16 and S["pan"].startswith("453212") and len(S["pvv"]) == 4 and S["pin"] and len(S["cvv"]) == 3 and len(S["cvv2"]) == 3 and "=" in S["track2"], S)
PAN, PIN, YYMM = S["pan"], S["pin"], S["expiryYYMM"]
link = Link()

print("== 1. red y tarjeta desconocida ==")
mti, f = link.send("0800", {7: time.strftime("%m%d%H%M%S"), 11: "000001", 70: "301"})
check("0800 eco -> 0810 39=00", mti == "0810" and f.get(39) == "00" and f.get(70) == "301", (mti, f))
mti, f = link.send("0100", base("4532120000000000", 1000, {14: YYMM}))
check("PAN desconocido -> 14", mti == "0110" and f.get(39) == "14", (mti, f.get(39)))

print("== 2. compra con banda: PIN y CVV verificados por el HSM ==")
pb = lab("/api/issuer-lab/pin-block", {"zpkBlob": KEYS["zpk"], "pan": PAN, "pin": PIN, "format": "00"})["pinBlock"]
mti, f = link.send("0100", base(PAN, 2500, {14: YYMM, 35: S["track2"], 52: pb}))
check("PIN correcto + CVV de banda -> 00 con codigo de aprobacion (DE38)", mti == "0110" and f.get(39) == "00" and len(f.get(38, "")) == 6, (mti, f.get(39), f.get(38)))
code1_rrn = f.get(37)
wrong = lab("/api/issuer-lab/pin-block", {"zpkBlob": KEYS["zpk"], "pan": S2["pan"], "pin": "0000" if S2["pin"] != "0000" else "1111", "format": "00"})["pinBlock"]
mti, f = link.send("0100", base(S2["pan"], 2500, {14: S2["expiryYYMM"], 35: S2["track2"], 52: wrong}))
check("PIN incorrecto -> 55", f.get(39) == "55", f.get(39))
bad_track = S2["track2"][:-3] + ("000" if S2["cvv"] != "000" else "111")
mti, f = link.send("0100", base(S2["pan"], 2500, {14: S2["expiryYYMM"], 35: bad_track}))
check("CVV de banda alterado -> 05", f.get(39) == "05", f.get(39))
mti, f = link.send("0100", base(S2["pan"], 2500, {14: "0101", 35: S2["track2"]}))
check("vigencia del mensaje distinta a la de la tarjeta -> 54", f.get(39) == "54", f.get(39))

print("== 3. comercio electronico: CVV2 ==")
mti, f = link.send("0100", base(PAN, 1500, {14: YYMM, 48: "CVV2=" + S["cvv2"]}, entry="010"))
check("CVV2 correcto -> 00", f.get(39) == "00", (f.get(39)))
mti, f = link.send("0100", base(S2["pan"], 1500, {14: S2["expiryYYMM"], 48: "CVV2=" + ("000" if S2["cvv2"] != "000" else "111")}, entry="010"))
check("CVV2 incorrecto -> N7", f.get(39) == "N7", f.get(39))
att = sql("select string_agg(channel||':'||response_code, ',' order by id) from authorization_attempts where card_id in (%d,%d)" % (C, C2))
check("los intentos ISO quedan en el historial de fraude con su canal", "ECOMMERCE:00" in att and "ECOMMERCE:N7" in att and "POS:00" in att and "POS:55" in att and "POS:05" in att, att)

print("== 4. chip EMV: ARQC verificado, ARPC devuelto ==")
tags = {"9F02": "%012d" % 3000, "9F03": "000000000000", "9F1A": "0484", "95": "0000008000", "5F2A": "0484", "9A": time.strftime("%y%m%d"), "9C": "00", "9F37": "1A2B3C4D", "82": "1800", "9F36": "0021", "9F10": "06010A03A00000"}
cdol = "".join(tags[t] for t in ["9F02", "9F03", "9F1A", "95", "5F2A", "9A", "9C", "9F37", "82", "9F36", "9F10"])
arqc = lab("/api/issuer-lab/arqc", {"imkBlob": KEYS["imk"], "pan": PAN, "psn": "00", "atc": "0021", "transactionData": cdol})["arqc"]
de55 = "".join(tlv(t, v) for t, v in tags.items()) + tlv("9F26", arqc) + tlv("5F34", "00")
mti, f = link.send("0100", base(PAN, 3000, {14: YYMM, 22: "051", 55: de55}))
check("ARQC valido -> 00 y DE55 con ARPC (tag 91, 8 bytes + ARC 3030)", f.get(39) == "00" and f.get(55, "").startswith("910A") and f.get(55, "").endswith("3030"), (f.get(39), f.get(55)))
de55_bad = "".join(tlv(t, v) for t, v in tags.items()) + tlv("9F26", "0000000000000000") + tlv("5F34", "00")
mti, f = link.send("0100", base(S2["pan"], 3000, {14: S2["expiryYYMM"], 22: "051", 55: de55_bad}))
check("ARQC falso -> 05, sin ARPC", f.get(39) == "05" and 55 not in f, (f.get(39), f.get(55)))
tags2 = dict(tags, **{"9F02": "%012d" % 3001})
de55_tamper = "".join(tlv(t, v) for t, v in tags2.items()) + tlv("9F26", arqc) + tlv("5F34", "00")
arqc2 = lab("/api/issuer-lab/arqc", {"imkBlob": KEYS["imk"], "pan": S2["pan"], "psn": "00", "atc": "0021", "transactionData": cdol})["arqc"]
de55_tamper = "".join(tlv(t, v) for t, v in tags2.items()) + tlv("9F26", arqc2) + tlv("5F34", "00")
mti, f = link.send("0100", base(S2["pan"], 3001, {14: S2["expiryYYMM"], 22: "051", 55: de55_tamper}))
check("monto alterado tras firmar (ARQC no cubre el nuevo monto) -> 05", f.get(39) == "05", f.get(39))

print("== 5. reverso y duplicados ==")
mti, f = link.send("0100", base(PAN, 4000, {14: YYMM, 35: S["track2"]}))
rrn = f.get(37); approval = f.get(38)
check("compra aprobada para reversar", f.get(39) == "00", f.get(39))
hold_before = sql("select status||'|'||coalesce(rrn,'') from authorization_holds where rrn='%s' order by id desc limit 1" % rrn)
mti, f = link.send("0400", base(PAN, 4000, {14: YYMM, 37: rrn, 90: "0100" + "000000" + time.strftime("%m%d%H%M%S") + "00000012345" + "00000000000"}))
hold_after = sql("select status from authorization_holds where rrn='%s' order by id desc limit 1" % rrn)
check("0400 por RRN -> 0410 00 y la retencion queda RELEASED", mti == "0410" and f.get(39) == "00" and hold_before.startswith("HELD|") and hold_after == "RELEASED", (mti, f.get(39), hold_before, hold_after))
mti, f = link.send("0400", base(PAN, 4000, {14: YYMM, 37: "999999999999"}))
check("reverso de una operacion inexistente -> 25", f.get(39) == "25", f.get(39))
dup = base(PAN, 700, {14: YYMM, 35: S["track2"]})
mti, f1 = link.send("0100", dup); mti, f2 = link.send("0100", dup)
n = sql("select count(*) from authorization_holds where rrn='%s'" % dup[37])
check("mismo STAN/fecha/terminal dos veces: misma respuesta, una sola retencion (idempotencia)", f1.get(39) == "00" and f2.get(39) == "00" and f2.get(38) == f1.get(38) and n == "1", (f1.get(38), f2.get(38), n))

print("== 6. aviso de la red (0120): la red aprobo por nosotros ==")
adv = base(PAN, 900, {14: YYMM, 38: "NET001"})
mti, f = link.send("0120", adv)
h = sql("select status||'|'||network_advice from authorization_holds where rrn='%s' order by id desc limit 1" % adv[37])
check("0120 -> 0130 00 y retencion marcada como aviso de red", mti == "0130" and f.get(39) == "00" and h == "HELD|true", (mti, f.get(39), h))

print("== 7. stand-in: el core no responde ==")
D, SD = new_card(pdeb, "Iso Debito " + RUN, deposit=0, external="1432")
st, rc = http("POST", "/cards/%d/core-deposit" % D, {"amount": 100, "reference": "ISO-TEST-" + RUN, "by": "verify_iso8583"})
check("la cuenta del core recibe fondos para la prueba (abono en Mifos)", st == 200 and float(rc.get("available", 0)) >= 23, (st, rc))
PAN_D, PIN_D, YYMM_D = SD["pan"], SD["pin"], SD["expiryYYMM"]
pbd = lab("/api/issuer-lab/pin-block", {"zpkBlob": KEYS["zpk"], "pan": PAN_D, "pin": PIN_D, "format": "00"})["pinBlock"]
mti, f = link.send("0100", base(PAN_D, 1100, {14: YYMM_D, 35: SD["track2"], 52: pbd}))
check("con el core arriba: compra de debito aprobada con retencion en Mifos", f.get(39) == "00" and sql("select coalesce(external_ref,'') <> '' and not stand_in from authorization_holds where rrn='%s'" % f.get(37)) == "t", (f.get(39), f.get(37)))
st, o = http("POST", "/core/outage", {"down": True}); check("se simula la caida del core", st == 200 and o["down"] is True, (st, o))
mti, f = link.send("0100", base(PAN_D, 1200, {14: YYMM_D, 35: SD["track2"], 52: pbd}))
rrn_si = f.get(37)
hs = sql("select status||'|'||stand_in||'|'||stand_in_pending||'|'||coalesce(external_ref,'-') from authorization_holds where rrn='%s'" % rrn_si)
check("core caido: aprobada en stand-in (00), retencion local pendiente de asentar en el core", f.get(39) == "00" and hs == "HELD|true|true|-", (f.get(39), hs))
mti, f = link.send("0100", base(PAN_D, 250000, {14: YYMM_D, 35: SD["track2"], 52: pbd}))
check("monto sobre el maximo de stand-in -> 91 (emisor no disponible)", f.get(39) == "91", f.get(39))
mti, f = link.send("0100", base(PAN_D, 500, {14: YYMM_D, 35: SD["track2"], 52: pbd}, pc="010000"))
check("retiro en cajero en stand-in -> 91 (canal no permitido)", f.get(39) == "91", f.get(39))
mti, f = link.send("0100", base(PAN, 800, {14: YYMM, 35: S["track2"]}))
check("una prepago (ledger local) sigue aprobando con el core caido, sin stand-in", f.get(39) == "00" and sql("select stand_in from authorization_holds where rrn='%s'" % f.get(37)) == "f", f.get(39))
st, ss = http("GET", "/standin/status"); check("estado de stand-in: 1 pendiente por 12.00", ss["pendingSettlement"] >= 1 and float(ss["pendingAmount"]) >= 12, ss)
st, o = http("POST", "/core/outage", {"down": False}); check("el core vuelve", o["down"] is False, o)
st, s2 = http("POST", "/standin/settle")
hs2 = sql("select stand_in||'|'||stand_in_pending||'|'||(external_ref is not null)||'|'||(stand_in_settled_at is not null) from authorization_holds where rrn='%s'" % rrn_si)
rej = sql("select detail from reconciliation_items where approval_code=(select approval_code from authorization_holds where rrn='%s')" % rrn_si)
check("asentamiento: la reserva se coloca en Mifos y la deuda queda saldada", s2.get("settled", 0) >= 1 and hs2 == "true|false|true|true", (s2, hs2, rej))
au = sql("select count(*) from audit_logs where action='STAND_IN_SETTLED' and entity_id=(select approval_code from authorization_holds where rrn='%s')" % rrn_si)
check("auditado", au == "1", au)
mti, f = link.send("0400", base(PAN_D, 1200, {14: YYMM_D, 37: rrn_si}))
check("el reverso del stand-in libera la reserva en el core", f.get(39) == "00" and sql("select status from authorization_holds where rrn='%s'" % rrn_si) == "RELEASED", f.get(39))

print("== 8. trazas para la consola ==")
st, rec = http("GET", "/iso/recent")
check("las ultimas tramas quedan enmascaradas para la consola", st == 200 and len(rec) >= 10 and all("******" in (r.get("pan") or "******") for r in rec if r.get("pan")), (st, len(rec)))
link.close()

print("== 9. limpieza ==")
for cid in (C, C2, D):
    subprocess.run([PSQL, "-h", "localhost", "-U", "postgres", "-d", "cms_mexico", "-c", """
with c as (select %d as id),
 d1 as (delete from ledger_entries where ledger_account_id in (select id from ledger_accounts where card_id in (select id from c))),
 d2 as (delete from ledger_accounts where card_id in (select id from c)),
 d3 as (delete from card_controls where card_id in (select id from c)),
 d4 as (delete from authorization_attempts where card_id in (select id from c)),
 d5 as (delete from fraud_alerts where card_id in (select id from c)),
 d6 as (delete from step_up_challenges where card_id in (select id from c)),
 d7 as (delete from plastics where card_id in (select id from c)),
 d8 as (delete from disputes where card_id in (select id from c)),
 d9 as (delete from guild_alerts where card_id in (select id from c)),
 d10 as (delete from reconciliation_items where card_id in (select id from c)),
 d11 as (delete from authorization_holds where card_id in (select id from c))
delete from cards where id in (select id from c)""" % cid], capture_output=True, text=True, env=dict(os.environ, PGPASSWORD="alodiga.123"))
check("tarjetas de prueba borradas", sql("select count(*) from cards where id in (%d,%d,%d)" % (C, C2, D)) == "0", "")

print(f"\nRESULTADO: {ok} OK, {bad} FAIL")
raise SystemExit(1 if bad else 0)
