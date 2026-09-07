package bank.cardissuing.transaction.domain;

import lombok.Getter;

/** ISO 8583 field 39 codes the authorizer answers with. A decline is a response, not an error. */
@Getter
public enum ResponseCode {
    APPROVED("00", "Approved"),
    INVALID_CARD("14", "Invalid card number"),
    INSUFFICIENT_FUNDS("51", "Insufficient funds"),
    EXPIRED_CARD("54", "Expired card"),
    EXCEEDS_LIMIT("61", "Exceeds withdrawal amount limit"),
    RESTRICTED_CARD("62", "Restricted card"),
    DUPLICATE("94", "Duplicate transmission"),
    SYSTEM_ERROR("96", "System malfunction");

    private final String code;
    private final String description;

    ResponseCode(String code, String description) {
        this.code = code;
        this.description = description;
    }
}
