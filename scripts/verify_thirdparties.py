# -*- coding: utf-8 -*-
"""Third parties end to end: the registry lists every provider with its health and contract,
contracts are edited and audited, and the messaging port carries the step-up code, the plastic
notices and the dispute outcome through the simulated provider, with an outage and a retry."""
import os, json, time, subprocess, urllib.request, urllib.error
import sys as _sys; _sys.path.insert(0, __import__("os").path.dirname(__file__)); from kyc_demo import identity

CMS = "http://localhost:8085/api"
PSQL = r"C:\Program Files\PostgreSQL\17\bin\psql.exe"
API_KEY = os.environ.get("CMS_API_KEY", "dev-api-key")
RUN = str(int(time.time()) % 100000)


def http(m, p, b=None):
    h = {"Content-Type": "application/json", "Accept": "*/*", "X-Api-Key": API_KEY}
    r = urllib.request.Request(CMS + p, data=json.dumps(b).encode() if b is not None else None, method=m, headers=h)
    try:
        with urllib.request.urlopen(r, timeout=90) as x:
            return x.status, json.loads(x.read() or b"null")
    except urllib.error.HTTPError as e:
        body = e.read()
        try: return e.code, json.loads(body or b"null")
        except Exception: return e.code, None


def sql(q):
    return subprocess.run([PSQL, "-h", "localhost", "-U", "postgres", "-d", "cms_mexico", "-tAc", q],
                          capture_output=True, text=True, env=dict(os.environ, PGPASSWORD="alodiga.123")).stdout.strip()



def purge_customers(names):
    """The test customers and every card they got (the auto-issued one included) leave with the run."""
    lst = ",".join("'%s'" % n.replace("'", "''") for n in names)
    subprocess.run([PSQL, "-h", "localhost", "-U", "postgres", "-d", "cms_mexico", "-c", """
with cu as (select id from customers where full_name in (%s)),
 c as (select id from cards where customer_id in (select id from cu)),
 m0 as (delete from outbound_messages where card_id in (select id from c)),
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
 d11 as (delete from authorization_holds where card_id in (select id from c)),
 d12 as (delete from cards where id in (select id from c)),
 d13 as (delete from kyc where customer_id in (select id from cu))
delete from customers where id in (select id from cu)""" % lst], capture_output=True, text=True, env=dict(os.environ, PGPASSWORD="alodiga.123"))

ok = bad = 0


def check(label, cond, detail=""):
    global ok, bad
    if cond:
        ok += 1; print(f"  OK   {label}")
    else:
        bad += 1; print(f"  FAIL {label}  {str(detail)[:360]}")


print("== 1. el registro de terceros ==")
st, tp = http("GET", "/thirdparties")
keys = [t["key"] for t in tp]
check("14 terceros en el registro, cada uno con salud y contrato sembrado", st == 200 and len(tp) == 14 and all(t["health"] and t["contract"] for t in tp), (st, keys))
by = {t["key"]: t for t in tp}
check("core bancario en modo fineract y sin señales de caída", by["CORE_BANKING"]["mode"] == "fineract" and by["CORE_BANKING"]["health"]["up"], by["CORE_BANKING"]["health"])
check("HSM responde al diagnóstico", by["HSM"]["health"]["up"], by["HSM"]["health"])
check("gremio y mensajería simulados y arriba", by["GUILD"]["mode"] == "simulated" and by["MESSAGING"]["mode"] == "simulated" and by["MESSAGING"]["health"]["up"], (by["GUILD"]["mode"], by["MESSAGING"]))
check("redes por archivo, mensajería/tesorería manual, buró sin integrar", by["NETWORK_VISA"]["integration"] == "FILE" and by["COURIER"]["integration"] == "MANUAL" and by["CREDIT_BUREAU"]["integration"] == "NONE" and not by["CREDIT_BUREAU"]["health"]["up"], "")
check("las variables de entorno se reportan sin exponer valores", all("set" in v and "value" not in v for t in tp for v in t["envVars"]) and any(v["set"] for v in by["CORE_BANKING"]["envVars"]), by["CORE_BANKING"]["envVars"])
check("bureau de personalización avisa que lleva la clave de desarrollo", by["PERSO_BUREAU"]["mode"] == "dev-key" and "desarrollo" in by["PERSO_BUREAU"]["health"]["detail"], by["PERSO_BUREAU"])
check("contratos sembrados PENDING con su lista de certificación", all(t["contract"]["status"] == "PENDING" or t["key"] in ("MESSAGING", "COURIER") for t in tp) and by["HSM"]["contract"]["checklistTotal"] >= 5, [(t["key"], t["contract"]["status"]) for t in tp])
st, hs = http("POST", "/thirdparties/HSM/check"); check("verificación en vivo del HSM", st == 200 and hs["health"]["up"], hs.get("health"))
st, cr = http("POST", "/thirdparties/CORE_BANKING/check"); check("verificación en vivo del core (llamada real a Mifos)", st == 200 and cr["health"]["up"] and "responde" in cr["health"]["detail"], cr.get("health"))
st, ia = http("POST", "/thirdparties/IAM/check"); print("     IAM:", ia["health"] if st == 200 else st)
st, nf = http("GET", "/thirdparties/NOPE"); check("tercero desconocido -> 404", st == 404, st)

print("== 2. contratos ==")
st, c = http("PUT", "/thirdparties/MESSAGING/contract", {"vendor": "Twilio MX", "contractRef": "CT-" + RUN, "status": "signed", "signedAt": "2026-09-01", "expiresAt": "2027-08-31", "contact": "cuentas@ejemplo.mx", "slaNotes": "OTP < 10 s, DLR obligatorio", "checklist": "[x] Remitente registrado\n[x] Plantillas aprobadas\n[ ] SLA de entrega\n[ ] DLR y reintentos\n[ ] Encargado de tratamiento", "by": "admin"})
check("contrato de mensajería firmado con 2 de 5 puntos certificados", st == 200 and c["status"] == "SIGNED" and c["checklistDone"] == 2 and c["checklistTotal"] == 5 and c["expired"] is False, (st, c))
st, bad_st = http("PUT", "/thirdparties/MESSAGING/contract", {"status": "FIRMADO"}); check("estado inválido -> 400", st == 400 and bad_st.get("errorCode") == "THIRD_PARTY_BAD_STATUS", (st, bad_st))
st, exp = http("PUT", "/thirdparties/COURIER/contract", {"vendor": "Estafeta", "status": "ACTIVE", "expiresAt": "2025-01-31", "by": "admin"})
check("un contrato vencido se marca", exp["expired"] is True and exp["status"] == "ACTIVE", exp)
au = sql("select count(*) from audit_logs where action='THIRD_PARTY_CONTRACT_UPDATED' and entity_name='ThirdPartyContract' and entity_id in ('MESSAGING','COURIER')")
check("cada edición de contrato queda en auditoría", int(au) >= 2, au)
st, one = http("GET", "/thirdparties/MESSAGING"); check("el registro refleja el contrato", one["contract"]["vendor"] == "Twilio MX" and one["contract"]["contractRef"] == "CT-" + RUN, one["contract"])

print("== 3. mensajería: prueba, código de un solo uso, avisos ==")
st, ms = http("GET", "/thirdparties/messaging/status"); check("proveedor simulado arriba", ms["mode"] == "simulated" and ms["up"] and ms["simulatorDown"] is False, ms)
st, t = http("POST", "/thirdparties/messages/test", {"channel": "WHATSAPP", "to": "5512345678", "text": "Prueba " + RUN, "by": "mesa"})
check("mensaje de prueba entregado por el simulador, destinatario enmascarado", st == 201 and t["status"] == "DELIVERED" and t["providerRef"].startswith("SIM-MSG-") and t["recipient"] == "******5678" and t["channel"] == "WHATSAPP", (st, t))
st, nt = http("POST", "/thirdparties/messages/test", {"to": "", "text": "x"}); check("sin destinatario -> 400", st == 400, st)

ppre = int(sql("select id from card_products where product_code='PRE-MX'"))
st, cu = http("POST", "/customers", {**identity("Terceros " + RUN), "fullName": "Terceros " + RUN, "phoneNumber": "5550000071", "cardLast4": "0000", "initialDeposit": 10})
st, k = http("POST", "/cards/issue", {"customerId": cu["id"], "productId": ppre, "embossedName": "TERCEROS " + RUN, "cardCategory": "VIRTUAL", "initialDeposit": 5})
C = k["id"]
for i in range(3):
    st, r = http("POST", "/authorization", {"cardId": C, "amount": 100, "merchantName": "Shop", "merchantId": "M-SHOP-" + RUN, "channel": "POS"})
st, r = http("POST", "/authorization", {"cardId": C, "amount": 3, "merchantName": "Shop", "merchantId": "M-SHOP-" + RUN, "channel": "ECOMMERCE"})
check("cuarto intento en e-commerce -> reto (1A) con código expuesto por expose-otp", r.get("responseCode") == "1A" and r.get("otpHint"), r)
otp = r.get("otpHint")
st, pm = http("GET", f"/thirdparties/messages?cardId={C}")
otps = [m for m in pm["content"] if m["template"] == "OTP"]
check("el código viajó por el proveedor al teléfono del cliente", len(otps) == 1 and otps[0]["status"] == "DELIVERED" and otps[0]["recipient"] == "******0071" and otps[0]["businessRef"] == "STEPUP", otps)
check("nuestra copia guarda el código enmascarado", "******" in otps[0]["text"] and otp not in otps[0]["text"], otps[0]["text"])
st, rt = http("POST", f"/thirdparties/messages/{otps[0]['id']}/retry"); check("un código ya entregado (y enmascarado) no se reenvía -> 409", st == 409 and rt.get("errorCode") in ("MESSAGE_ALREADY_SENT", "MESSAGE_OTP_NOT_RETRYABLE"), (st, rt))

st, pv = http("POST", "/plastics", {"cardId": C, "reason": "NEW", "deliveryAddress": "Av. Reforma 1, CDMX", "pinMailer": False, "by": "mesa"})
st, b = http("POST", "/plastics/batches", {"manufacturer": "IDEMIA-MX", "by": "ops"})
http("POST", f"/plastics/batches/{b['id']}/send", {"by": "ops"})
http("POST", f"/plastics/batches/{b['id']}/produced", {"failedPlasticIds": [], "by": "bureau"})
st, sh = http("POST", f"/plastics/{pv['id']}/ship", {"carrier": "DHL", "trackingNumber": "MX-" + RUN, "by": "ops"})
st, dv = http("POST", f"/plastics/{pv['id']}/deliver", {"by": "courier"})
check("plástico enviado y entregado", sh.get("status") == "SHIPPED" and dv.get("status") == "DELIVERED", (sh, dv))
st, pm = http("GET", f"/thirdparties/messages?cardId={C}")
tpl = {m["template"]: m for m in pm["content"]}
check("avisos de envío (con guía) y entrega al cliente", "CARD_SHIPPED" in tpl and "MX-" + RUN in tpl["CARD_SHIPPED"]["text"] and "DHL" in tpl["CARD_SHIPPED"]["text"] and "CARD_DELIVERED" in tpl and tpl["CARD_DELIVERED"]["status"] == "DELIVERED", list(tpl))

# a clean card for the dispute (the first one carries the fraud engine's memory of its declines)
st, cu2 = http("POST", "/customers", {**identity("Terceros B " + RUN), "fullName": "Terceros B " + RUN, "phoneNumber": "5550000072", "cardLast4": "0000", "initialDeposit": 10})
st, k2 = http("POST", "/cards/issue", {"customerId": cu2["id"], "productId": ppre, "embossedName": "TERCEROS B " + RUN, "cardCategory": "VIRTUAL", "initialDeposit": 100})
C2 = k2["id"]
sql("update cards set created_at = now() - interval '30 days' where id=%d" % C2)
st, r = http("POST", "/authorization", {"cardId": C2, "amount": 20, "merchantName": "Shop", "merchantId": "M-OK-" + RUN, "channel": "POS"})
code = r.get("approvalCode")
http("POST", f"/authorization/{code}/capture")
st, d = http("POST", "/disputes", {"cardId": C2, "approvalCode": code, "reasonCode": "13.1", "description": "terceros e2e", "provisionalCredit": False, "openedBy": "mesa"})
check("compra de 20 capturada y aclaración abierta en la segunda tarjeta", r.get("responseCode") == "00" and st == 201, (r, st, d))
st, rv = http("POST", f"/disputes/{d['id']}/resolve", {"outcome": "CUSTOMER", "note": "sin respuesta", "by": "mesa"})
st, pm = http("GET", f"/thirdparties/messages?cardId={C2}")
dm = [m for m in pm["content"] if m["template"] == "DISPUTE_RESOLVED"]
check("el cliente recibe el resultado de la aclaración", rv.get("status") in ("RESOLVED", "CLOSED", "RESOLVED_CUSTOMER") and len(dm) == 1 and str(d["id"]) in dm[0]["text"] and "CUSTOMER" in dm[0]["text"], (rv.get("status"), dm))

print("== 4. caída del proveedor y reintento ==")
st, sd = http("POST", "/thirdparties/messaging/simulator", {"down": True}); check("simulador apagado", sd["up"] is False and sd["simulatorDown"] is True, sd)
st, q = http("POST", "/thirdparties/messages/test", {"to": "5599999999", "text": "en cola " + RUN, "by": "mesa"})
check("con el proveedor caído el mensaje queda QUEUED con el error", st == 201 and q["status"] == "QUEUED" and q["attempts"] == 1 and "switched off" in (q["lastError"] or ""), q)
st, ms = http("GET", "/thirdparties/messaging/status"); check("la cola se ve en el estado", ms["queued"] >= 1, ms)
st, r = http("POST", "/authorization", {"cardId": C, "amount": 3, "merchantName": "Shop", "merchantId": "M-SHOP-" + RUN, "channel": "ECOMMERCE"})
check("el autorizador sigue respondiendo aunque el proveedor esté caído", r.get("responseCode") in ("1A", "59", "00"), r)
http("POST", "/thirdparties/messaging/simulator", {"down": False})
st, rt = http("POST", f"/thirdparties/messages/{q['id']}/retry"); check("reintento manual: entregado al segundo intento", st == 200 and rt["status"] == "DELIVERED" and rt["attempts"] == 2 and rt["lastError"] is None, (st, rt))
st, rt2 = http("POST", f"/thirdparties/messages/{q['id']}/retry"); check("no se reenvía lo ya entregado -> 409", st == 409, st)
st, pg = http("GET", "/thirdparties/messages?page=0&size=2"); check("la bandeja se pagina en el servidor", len(pg["content"]) == 2 and pg["totalElements"] >= 5 and pg["hasNext"] is True, (len(pg["content"]), pg["totalElements"]))
st, fl = http("GET", "/thirdparties/messages?status=DELIVERED&page=0&size=50"); check("filtro por estado", all(m["status"] == "DELIVERED" for m in fl["content"]) and fl["totalElements"] >= 4, fl["totalElements"])

print("== 5. limpieza ==")
http("PUT", "/thirdparties/COURIER/contract", {"vendor": "Estafeta / DHL / mensajería local", "status": "PENDING", "expiresAt": "", "by": "admin"})
subprocess.run([PSQL, "-h", "localhost", "-U", "postgres", "-d", "cms_mexico", "-c", """
with c as (select unnest(array[%d, %d]) as id),
 m0 as (delete from outbound_messages where card_id in (select id from c) or business_ref='TEST' or recipient in ('5512345678','5599999999')),
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
delete from cards where id in (select id from c)""" % (C, C2)], capture_output=True, text=True, env=dict(os.environ, PGPASSWORD="alodiga.123"))
purge_customers(["Terceros " + RUN, "Terceros B " + RUN])
check("datos de prueba borrados", sql("select count(*) from cards where id in (%d,%d)" % (C, C2)) == "0" and sql("select count(*) from outbound_messages where card_id in (%d,%d)" % (C, C2)) == "0", "")

print(f"\nRESULTADO: {ok} OK, {bad} FAIL")
raise SystemExit(1 if bad else 0)
