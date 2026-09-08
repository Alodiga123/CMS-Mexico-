package bank.cardissuing.transaction.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.List;

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
    /** Technical reason, for the switch and the log. */
    private String message;
    /** What the cardholder should be told, in their words. */
    private String customerMessage;
    private String cardType;
    private String holdStatus;

    /** 0-100. Present on every decision so a decline is explainable and an approval auditable. */
    private Integer riskScore;
    private List<String> riskReasons;
    /** Set on a 1A: the step-up challenge the cardholder must pass, then retry with stepUpToken. */
    private String challengeId;
    /** Only when fraud.step-up.expose-otp is on (dev/test): the code that would have been sent. */
    private String otpHint;

    public static AuthorizationResponse approve(String approvalCode, BigDecimal availableAfter, String cardType) {
        return AuthorizationResponse.builder()
                .approved(true)
                .responseCode(ResponseCode.APPROVED.getCode())
                .approvalCode(approvalCode)
                .amount(availableAfter)
                .message(ResponseCode.APPROVED.getDescription())
                .customerMessage(ResponseCode.APPROVED.getCustomerMessage())
                .cardType(cardType)
                .holdStatus("HELD")
                .build();
    }

    public static AuthorizationResponse decline(ResponseCode code, String detail, String cardType) {
        return AuthorizationResponse.builder()
                .approved(false)
                .responseCode(code.getCode())
                .message(detail != null ? code.getDescription() + ": " + detail : code.getDescription())
                .customerMessage(code.getCustomerMessage())
                .cardType(cardType)
                .build();
    }

    /** Legacy one-argument decline kept so existing callers keep compiling. */
    public static AuthorizationResponse decline(String reason) {
        return decline(ResponseCode.INSUFFICIENT_FUNDS, reason, null);
    }

    public AuthorizationResponse withRisk(int score, List<String> reasons) {
        this.riskScore = score;
        this.riskReasons = reasons;
        return this;
    }
}
