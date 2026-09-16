-- Bitácora de auditoría de SOLO INSERCIÓN (tamper-prevention, PCI DSS 10.5).
-- Complementa el hash-chain de AuditService (tamper-evidence): aquí se IMPIDE a nivel de base de
-- datos cualquier UPDATE o DELETE sobre audit_logs, de modo que ni la aplicación ni un actor con
-- las credenciales de la app puedan alterar o borrar registros. La app solo hace INSERT.
--
-- Aplicar con el rol dueño de la tabla (cms):
--   psql -h 10.10.40.10 -U cms -d cms_mexico -f audit_logs_immutable.sql
--
-- Residual conocido (para cierre pleno de PCI 10.5): el dueño de la tabla aún podría DROP del
-- trigger; el cierre completo requiere separar el dueño de la tabla del rol de la app (la app con
-- solo INSERT/SELECT), export append-only a WORM/SIEM y retención de 12 meses. Ver docs.
--
-- Purga por retención: como el DELETE está bloqueado, una purga controlada de registros fuera del
-- periodo de retención debe hacerla un mantenimiento privilegiado que desactive el trigger, purgue
-- y lo vuelva a crear (o exportar a WORM y truncar). No se hace automáticamente a propósito.

CREATE OR REPLACE FUNCTION audit_logs_no_modify() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'audit_logs es de solo inserción: % no permitido (PCI DSS 10.5 tamper-prevention)', TG_OP;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_audit_logs_no_modify ON audit_logs;
CREATE TRIGGER trg_audit_logs_no_modify
    BEFORE UPDATE OR DELETE ON audit_logs
    FOR EACH STATEMENT EXECUTE FUNCTION audit_logs_no_modify();
