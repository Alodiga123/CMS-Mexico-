package bank.cardissuing.customer.infrastructure.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CustomerResponse {
    private Long id;
    private String fullName;
    private String phoneNumber;
    private String kycStatus;
    private Long cardId;
    private String cardLast4;
    private String cardStatus;
    private BigDecimal balance;
}
