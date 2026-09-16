# M2 — TLS en tránsito de la consola/API del CMS (evidencia y endurecimiento)

Requisito: PCI DSS 4.2.1 (criptografía fuerte en tránsito; TLS 1.2+/1.3). Cierre del hallazgo M2
del audit interno del 2026-09-16.

## Arquitectura del tránsito

```
Navegador ──HTTPS (TLS 1.3)──▶ Proxy de borde (200.73.199.53, Let's Encrypt)
   └─▶ nginx consola (10.10.20.10:8085, HTTP interno) ──▶ CMS app (10.10.30.10:8085, HTTP interno)
```

El TLS se termina en el proxy de borde. Los saltos internos (borde→consola→app) van por HTTP
dentro del segmento de red interno de confianza. El canal ISO 8583 adquirente↔CMS va aparte, con
TLS + TLS mutuo + allowlist (ver DEPLOY/TRAZA).

## Evidencia del borde (2026-09-16)

`openssl s_client` contra los dominios públicos:

- `cms-mexico.alocashfintech.com:443` → **Protocol: TLSv1.3**, Cipher **TLS_AES_256_GCM_SHA384**,
  cert **CN=cms-mexico.alocashfintech.com** (issuer Let's Encrypt), **Verify return code: 0 (ok)**.
- `adquiriencia-mexico.alocashfintech.com:443` → idéntico (TLS 1.3, mismo cipher, cert válido).

TLS 1.0/1.1: el cliente openssl local ya no los ofrece (deshabilitados por política), por lo que su
rechazo debe confirmarse con un escáner externo (p. ej. SSL Labs) sobre el proxy de borde, que no
vive en estos hosts.

## Cambios aplicados

1. **App CMS** (`application.properties`): `server.forward-headers-strategy=framework`. Sin esto la
   app veía todo como HTTP plano detrás del proxy y `request.isSecure()` era `false`, por lo que
   **HSTS no se emitía**. Verificado: con `X-Forwarded-Proto: https` la respuesta de `/api` ya
   incluye `Strict-Transport-Security`, junto con CSP, X-Content-Type-Options y X-Frame-Options (M3).
2. **nginx consola** (`~/cms-console/nginx.conf` en 10.10.20.10): preserva el `X-Forwarded-Proto`
   del borde (map: usa el entrante si viene, si no el esquema local) en vez de sobrescribirlo con
   `$scheme` (que era `http`). Así el proto real (https) llega hasta la app. Además la consola ya
   envía HSTS/CSP en el documento (M3).

## Residual (para cierre pleno)

- Los saltos internos (borde→consola→app) son HTTP dentro del segmento de confianza. Para TLS
  extremo a extremo, terminar TLS en la app (`server.ssl.*`) o mTLS en los saltos internos —
  decisión de infraestructura.
- Config del proxy de borde (200.73.199.53): confirmar y evidenciar TLS 1.2/1.3, cifradores
  fuertes y HSTS; está fuera de estos hosts (lo administra el equipo del proxy público).
