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
    private String customerType;
    private String displayName;
    private String fullName;
    private String businessName;
    private String phoneNumber;
    private String email;
    private String taxId;
    private String legalRepresentativeName;
    private String kycStatus;
    private Long cardId;
    private String cardLast4;
    private String cardStatus;
    private BigDecimal balance;
}
