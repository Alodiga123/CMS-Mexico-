package bank.cardissuing.customer.infrastructure.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class CustomerCreateRequest {

    // PERSONA_NATURAL (default) o PERSONA_JURIDICA
    private String customerType;

    @NotBlank(message = "Full name is required")
    private String fullName;

    @NotBlank(message = "Phone number is required")
    private String phoneNumber;

    private String email;

    private String address;

    // RFC: persona física u homoclave de persona moral
    private String taxId;

    // --- Solo requerido cuando customerType = PERSONA_JURIDICA ---
    private String businessName; // Razón social

    private String legalRepresentativeName;

    private String legalRepresentativeTaxId;

    private String cardLast4;

    @PositiveOrZero(message = "Initial deposit must be zero or positive")
    private BigDecimal initialDeposit;
}
