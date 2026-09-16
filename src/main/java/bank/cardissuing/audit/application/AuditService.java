package bank.cardissuing.audit.application;

public interface AuditService {
    void log(String action, String entityName, String entityId, String performBy);

    /** Verifica la integridad de la cadena de hash de la bitácora (tamper-evidence). */
    ChainCheck verifyChain();

    /**
     * Resultado de la verificación de la cadena de auditoría.
     * @param ok true si la cadena está íntegra
     * @param checked cuántos registros encadenados se verificaron
     * @param brokenAtId id del primer registro con la cadena rota (null si íntegra)
     * @param detail descripción legible
     */
    record ChainCheck(boolean ok, long checked, Long brokenAtId, String detail) { }
}
