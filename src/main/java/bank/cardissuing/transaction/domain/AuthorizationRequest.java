package bank.cardissuing.transaction.domain;

import bank.cardissuing.card.domain.Channel;
import bank.cardissuing.common.exception.BusinessException;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;

@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class AuthorizationRequest {

    @NotNull(message = "Card ID is required")
    private Long cardId;

    @NotNull(message = "Amount is required")
    @DecimalMin(value = "0.01", message = "Amount must be greater than 0")
    private BigDecimal amount;

    @NotBlank(message = "Merchant name is required")
    @Size(max = 100, message = "Merchant name must not exceed 100 characters")
    private String merchantName;

    @Size(max = 50, message = "Merchant ID must not exceed 50 characters")
    private String merchantId;

    /** PURCHASE (default), ATM_WITHDRAWAL, ECOMMERCE, PREAUTH. Free text for now; rules will key on it. */
    @Size(max = 30)
    private String transactionType;

    /** POS, ATM, ECOMMERCE or CONTACTLESS. Derived from transactionType when absent. */
    @Size(max = 20)
    private String channel;

    /** Merchant country (ISO code or the product's own convention). Absent means domestic. */
    @Size(max = 3)
    private String countryCode;

    /** ISO 8583 references when the request came over the network: retrieval reference (37), STAN (11), acquirer (32). */
    @Size(max = 12)
    private String rrn;

    @Size(max = 6)
    private String stan;

    @Size(max = 11)
    private String acquirerId;

    /** Token of a step-up challenge the cardholder already passed, when retrying after a 1A. */
    @Size(max = 80)
    private String stepUpToken;

    public String transactionTypeOrDefault() {
        return transactionType == null || transactionType.isBlank() ? "PURCHASE" : transactionType;
    }

    /** An unknown channel is rejected, never silently treated as POS: that would bypass a control. */
    public Channel channelOrDefault() {
        if (channel != null && !channel.isBlank()) {
            try {
                return Channel.valueOf(channel.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new BusinessException("INVALID_CHANNEL", "Unknown channel '" + channel + "'", HttpStatus.BAD_REQUEST);
            }
        }
        return switch (transactionTypeOrDefault().toUpperCase()) {
            case "ATM_WITHDRAWAL" -> Channel.ATM;
            case "ECOMMERCE" -> Channel.ECOMMERCE;
            default -> Channel.POS;
        };
    }
}
