package bank.cardissuing.transaction.domain;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
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

    public String transactionTypeOrDefault() {
        return transactionType == null || transactionType.isBlank() ? "PURCHASE" : transactionType;
    }
}
