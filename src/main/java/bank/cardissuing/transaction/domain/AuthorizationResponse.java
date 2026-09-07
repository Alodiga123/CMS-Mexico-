package bank.cardissuing.transaction.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class AuthorizationResponse {

    private boolean approved;
    private String responseCode;
    private String approvalCode;
    /** Funds available after this decision. Kept as {@code amount} for API compatibility. */
    private BigDecimal amount;
    private String message;
    private String cardType;
    private String holdStatus;

    public static AuthorizationResponse approve(String approvalCode, BigDecimal availableAfter, String cardType) {
        return AuthorizationResponse.builder()
                .approved(true)
                .responseCode(ResponseCode.APPROVED.getCode())
                .approvalCode(approvalCode)
                .amount(availableAfter)
                .message(ResponseCode.APPROVED.getDescription())
                .cardType(cardType)
                .holdStatus("HELD")
                .build();
    }

    public static AuthorizationResponse decline(ResponseCode code, String detail, String cardType) {
        return AuthorizationResponse.builder()
                .approved(false)
                .responseCode(code.getCode())
                .message(detail != null ? code.getDescription() + ": " + detail : code.getDescription())
                .cardType(cardType)
                .build();
    }

    /** Legacy one-argument decline kept so existing callers keep compiling. */
    public static AuthorizationResponse decline(String reason) {
        return decline(ResponseCode.INSUFFICIENT_FUNDS, reason, null);
    }
}
