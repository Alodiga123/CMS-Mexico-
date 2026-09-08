# -*- coding: utf-8 -*-
"""Server-side paging of cards, authorization attempts and fraud alerts: envelope shape, page
arithmetic, filters, size cap, and backward compatibility (no params -> plain array)."""
import os, json, urllib.request, urllib.error

CMS = "http://localhost:8085/api"


def get(p):
    try:
        with urllib.request.urlopen(urllib.request.Request(CMS + p, headers={"Accept": "application/json", "X-Api-Key": os.environ.get("CMS_API_KEY", "dev-api-key")}), timeout=60) as x:
            return x.status, json.loads(x.read() or b"null")
    except urllib.error.HTTPError as e:
        try: return e.code, json.loads(e.read() or b"null")
        except Exception: return e.code, None


ok = bad = 0


def check(label, cond, detail=""):
    global ok, bad
    if cond:
        ok += 1; print(f"  OK   {label}")
    else:
        bad += 1; print(f"  FAIL {label}  {str(detail)[:300]}")


print("== 1. tarjetas ==")
st, all_ = get("/cards")
check("sin parametros: arreglo completo (compatibilidad)", st == 200 and isinstance(all_, list) and len(all_) > 20, (st, type(all_).__name__))
total = len(all_)
st, p0 = get("/cards?page=0&size=10")
check("page=0&size=10: sobre con content, page, size, totalElements, totalPages, hasNext", st == 200 and isinstance(p0, dict) and len(p0["content"]) == 10 and p0["page"] == 0 and p0["size"] == 10 and p0["totalElements"] == total and p0["totalPages"] == -(-total // 10) and p0["hasNext"] is True, p0 if st != 200 else {k: p0.get(k) for k in ("page", "size", "totalElements", "totalPages", "hasNext")})
st, p1 = get("/cards?page=1&size=10")
ids0 = [c["id"] for c in p0["content"]]; ids1 = [c["id"] for c in p1["content"]]
check("la segunda pagina no repite ids y sigue el orden (mas recientes primero)", not set(ids0) & set(ids1) and ids0 == sorted(ids0, reverse=True) and max(ids1) < min(ids0), (ids0[:3], ids1[:3]))
last = p0["totalPages"] - 1
st, pl = get(f"/cards?page={last}&size=10")
check("ultima pagina: hasNext=false y resto de filas", pl["hasNext"] is False and 0 < len(pl["content"]) <= 10 and len(pl["content"]) == total - last * 10, (len(pl["content"]), total))
st, pbig = get("/cards?page=0&size=99999")
check("size se acota a 500", pbig["size"] == 500, pbig.get("size"))
st, pneg = get("/cards?page=-3&size=0")
check("page negativo y size 0 caen a 0 y 20", pneg["page"] == 0 and pneg["size"] == 20, (pneg.get("page"), pneg.get("size")))
pid = all_[0].get("productName")
st, prods = get("/products")
prod = next((x for x in prods if x["productName"] == pid), prods[0])
st, pf = get(f"/cards?productId={prod['id']}&page=0&size=50")
check("filtro por producto: todas las filas son de ese producto y el total coincide con la lista completa", all(c["productName"] == prod["productName"] for c in pf["content"]) and pf["totalElements"] == sum(1 for c in all_ if c["productName"] == prod["productName"]), (pf["totalElements"], prod["productName"]))
st, ps = get("/cards?status=BLOCKED&page=0&size=50")
check("filtro por estado", st == 200 and all(c["status"] == "BLOCKED" for c in ps["content"]) and ps["totalElements"] == sum(1 for c in all_ if c["status"] == "BLOCKED"), ps.get("totalElements"))
l4 = all_[0]["last4"]
st, pl4 = get(f"/cards?last4={l4}")
check("filtro por last4 sin page: sobre con todas las coincidencias", isinstance(pl4, dict) and all(c["last4"] == l4 for c in pl4["content"]) and pl4["totalElements"] >= 1, pl4.get("totalElements"))

print("== 2. intentos de autorizacion ==")
card = next((c["id"] for c in all_ if c["status"] == "ACTIVE"), all_[0]["id"])
st, lst = get(f"/fraud/attempts?cardId={card}")
check("con cardId y sin page: arreglo (compatibilidad)", st == 200 and isinstance(lst, list), (st, type(lst).__name__))
st, pa = get("/fraud/attempts?page=0&size=15")
check("sin cardId: pagina global de intentos, mas recientes primero", st == 200 and isinstance(pa, dict) and len(pa["content"]) == 15 and pa["totalElements"] > 15 and pa["content"][0]["at"] >= pa["content"][-1]["at"], (st, pa.get("totalElements")))
st, pa2 = get("/fraud/attempts?page=1&size=15")
check("segunda pagina distinta", not {a["id"] for a in pa["content"]} & {a["id"] for a in pa2["content"]}, "")
st, pd = get("/fraud/attempts")
check("sin cardId ni page: los 100 mas recientes en forma de pagina", isinstance(pd, dict) and pd["size"] == 100 and pd["page"] == 0, (pd.get("size") if isinstance(pd, dict) else pd))
st, pc = get(f"/fraud/attempts?cardId={card}&page=0&size=5")
check("cardId con page: pagina de esa tarjeta", isinstance(pc, dict) and pc["totalElements"] == len(lst) if len(lst) <= 100 else True, (pc.get("totalElements"), len(lst)))

print("== 3. alertas de fraude ==")
st, al = get("/fraud/alerts?status=ALL")
check("sin page: arreglo (compatibilidad)", isinstance(al, list) and len(al) > 20, len(al) if isinstance(al, list) else al)
st, pal = get("/fraud/alerts?status=ALL&page=0&size=20")
check("pagina 0 de 20 con el total de la lista", isinstance(pal, dict) and len(pal["content"]) == 20 and pal["totalElements"] == len(al), (pal.get("totalElements"), len(al)))
st, pop = get("/fraud/alerts?status=OPEN&page=0&size=10")
check("filtro OPEN paginado: todas abiertas, total = abiertas de la lista", all(a["status"] == "OPEN" for a in pop["content"]) and pop["totalElements"] == sum(1 for a in al if a["status"] == "OPEN"), (pop.get("totalElements")))
st, bad_st = get("/fraud/alerts?status=NOPE&page=0&size=10")
check("estado desconocido no es un 500", st in (400, 422, 404), st)

print(f"\nRESULTADO: {ok} OK, {bad} FAIL")
raise SystemExit(1 if bad else 0)
