# Card Issuing & Ledger Core System

**A production-grade backend simulation of a Debit Card Issuing system, built with Modular Monolith architecture inspired by DDD principles.**

> This project demonstrates how to build a Fintech Core from scratch, focusing on Data Integrity, Auditability, and Banking Standards. Designed for team collaboration with clear module boundaries.

---

##  System Architecture
**Style:** Modular Monolith  
**Approach:** Inspired by Domain-Driven Design principles

### Why Modular Monolith?
*   **Team-friendly:** Each module can be worked on independently, reducing merge conflicts.
*   **Clear boundaries:** Logic for Card stays within `card/`, not scattered across the codebase.
*   **Easy onboarding:** New developers only need to understand one module at a time.
*   **Production-ready:** Used by real fintech companies for maintainability at scale.

### Module Structure
The system is divided into independent modules with strict boundaries:
*   **`customer/`**: Manages KYC (Know Your Customer) lifecycles.
*   **`card/`**: Handles Card State Machines (PCI-DSS compliant storage).
*   **`ledger/`**: Manages immutable financial records and balance derivation.
*   **`transaction/`**: Processes authorization requests with fraud detection.

---

##  Key Technical Features

### 1. Immutable Ledger (Append-Only)
*   **Constraint:** Financial records are strictly immutable. Updates and Deletions are blocked at the Entity level using JPA Lifecycle Events (`@PreUpdate`, `@PreRemove`).
*   **Balance Derivation:** Account balance is never stored as a mutable field. It is calculated on-the-fly using SQL Aggregation to ensure 100% consistency with the transaction history.

### 2. Strict State Machines
*   **Card Lifecycle:** Enposes strict transitions (e.g., `CREATED` -> `ACTIVE` -> `BLOCKED`). Invalid transitions (e.g., activating a blocked card) are rejected with domain exceptions.
*   **KYC Verification:** Customers must pass KYC verification before a card can be issued.

### 3. Banking Standards Compliance
*   **Precision:** All monetary values use `BigDecimal` to prevent floating-point grounding errors.
*   **Security:** Only the `last4` digits of cards are stored, strictly following data minimization principles.

### 4. Idempotency & Safe Retries
*   **Problem:** Network failures can cause duplicate requests (e.g., clicking "Issue Card" twice).
*   **Solution:** Implemented **Idempotency Key** mechanism.
    *   Requests with the same key are processed exactly once.
    *   Subsequent requests return the cached response without re-executing logic or side effects.

### 5. Async Audit Logging
*   **Standard:** All critical actions (Issuing, Blocking, KYC updates) are traced for security & compliance.
*   **Performance:** Uses `@Async` and `REQUIRES_NEW` propagation to ensure logging happens in a separate transaction without blocking the main user flow.

---

## Tech Stack
*   **Java 17**
*   **Spring Boot 3.2.2** (Data JPA, Validation)
*   **JUnit 5 & Mockito** (Comprehensive Unit & Service Layer Testing)

---

##  Quality Assurance
*   **Unit Tests:** Focus on domain invariants (Entity logic).
*   **Service Tests:** Verify business flows with mocked dependencies.

_Built by LeHuyTuong_# CMS-Alodiga

## Configuración y secretos

La aplicación no lleva credenciales en el código. Todo se toma de variables de entorno
(ver `.env.example`); `application.properties` solo trae valores por defecto locales y
la contraseña de base de datos no tiene valor por defecto.

- `.env` está en `.gitignore`. Nunca se versiona.
- Base local: `DB_URL=jdbc:postgresql://localhost:5432/cms_mexico`, `DB_USERNAME`, `DB_PASSWORD`.
- Core Mifos/Fineract: `CORE_MODE=fineract` más `FINERACT_USER` / `FINERACT_PASSWORD`.
- Bureau de plásticos: `PLASTICS_MANUFACTURER_KEY` (AES-256 en base64). La llave de desarrollo
  que trae `application.properties` no sirve para producción.

Histórico: hasta septiembre de 2026 el repositorio incluía un `.env` y valores por defecto con
la credencial de un Postgres remoto (`200.73.194.40`). Se retiraron del árbol, pero siguen en
el historial de git; esa contraseña debe considerarse comprometida y rotarse en el servidor.

## Documentación

- [Compensación y liquidación con los archivos de la red](docs/compensacion-liquidacion.md)
- [Conexión con los sistemas antifraude del gremio (ACT-094)](docs/guild-antifraude.md)
- [Canal ISO 8583, validación criptográfica y stand-in](docs/iso8583-criptografia-standin.md)
- [Seguridad de la consola y la API con el IAM](docs/seguridad-iam.md)
