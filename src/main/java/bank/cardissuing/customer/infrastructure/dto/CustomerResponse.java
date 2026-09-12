package bank.cardissuing.customer.infrastructure.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CustomerResponse {
    private Long id;
    private String fullName;
    private String phoneNumber;
    private String email;
    private String curpMasked;
    private String rfcMasked;
    private String birthDate;
    private String state;
    private boolean pep;
    private String kycStatus;
    private String kycRiskLevel;
    /** Codes of the checks that did not pass in the last run. */
    private List<String> kycFailed;
    private Map<String, Object> kyc;
    private Long cardId;
    private String cardLast4;
    private String cardStatus;
    private BigDecimal balance;
}
