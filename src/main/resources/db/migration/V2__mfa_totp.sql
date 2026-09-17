-- Segundo factor (TOTP) de la consola del CMS (A3, PCI DSS 8.4.2/8.5). Un secreto por usuario,
-- cifrado en reposo (AES-GCM); el PIN/secreto nunca se guarda en claro.
CREATE TABLE IF NOT EXISTS cms_mfa (
    username    VARCHAR(255) PRIMARY KEY,
    secret_enc  TEXT        NOT NULL,
    enabled     BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMP   NOT NULL DEFAULT now(),
    enabled_at  TIMESTAMP
);
