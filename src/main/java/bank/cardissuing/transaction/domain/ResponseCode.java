package bank.cardissuing.transaction.domain;

import lombok.Getter;

/**
 * ISO 8583 field 39 codes the authorizer answers with. A decline is a response, not an
 * error. Each carries the technical description and the words the cardholder should
 * see -- the "explicación funcional de cada rechazo".
 */
@Getter
public enum ResponseCode {
    APPROVED("00", "Approved", "Operación aprobada"),
    INVALID_CARD("14", "Invalid card number", "Tarjeta inválida"),
    AUTHENTICATION_REQUIRED("1A", "Additional customer authentication required", "Confirma la operación con el código que te enviamos"),
    INSUFFICIENT_FUNDS("51", "Insufficient funds", "Fondos insuficientes"),
    EXPIRED_CARD("54", "Expired card", "Tarjeta vencida"),
    NOT_PERMITTED("57", "Transaction not permitted to cardholder", "Operación no permitida para esta tarjeta"),
    SUSPECTED_FRAUD("59", "Suspected fraud", "Operación rechazada por seguridad; contacta a tu banco"),
    EXCEEDS_LIMIT("61", "Exceeds withdrawal amount limit", "Excede el límite permitido"),
    RESTRICTED_CARD("62", "Restricted card", "Tarjeta restringida"),
    DUPLICATE("94", "Duplicate transmission", "Operación duplicada"),
    SYSTEM_ERROR("96", "System malfunction", "Error del sistema, intenta más tarde"),
    DO_NOT_HONOR("05", "Do not honor", "Operación rechazada; contacta a tu banco"),
    UNABLE_TO_LOCATE("25", "Unable to locate record on file", "No se encontró la operación original"),
    INCORRECT_PIN("55", "Incorrect PIN", "NIP incorrecto"),
    PIN_TRIES_EXCEEDED("75", "Allowable number of PIN tries exceeded", "Excediste los intentos de NIP"),
    ISSUER_UNAVAILABLE("91", "Issuer or switch is inoperative", "Servicio no disponible por el momento, intenta más tarde"),
    CVV2_MISMATCH("N7", "Decline for CVV2 failure", "El código de seguridad no coincide");

    private final String code;
    private final String description;
    private final String customerMessage;

    ResponseCode(String code, String description, String customerMessage) {
        this.code = code;
        this.description = description;
        this.customerMessage = customerMessage;
    }
}
