package bank.cardissuing.customer.infrastructure.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * What the console (or an onboarding channel) sends to register a cardholder. The identity block
 * feeds the KYC; a request without it leaves the customer PENDING and no card can be issued.
 */
@Data
public class CustomerCreateRequest {

    /** Kept for callers that send the name in one field; ignored when firstNames/paternalSurname come. */
    private String fullName;
    private String firstNames;
    private String paternalSurname;
    private String maternalSurname;

    @NotBlank(message = "Phone number is required")
    private String phoneNumber;
    private String email;

    private LocalDate birthDate;
    /** H or M */
    private String sex;
    private String curp;
    private String rfc;
    private String nationality;
    private String occupation;
    private String addressLine;
    private String postalCode;
    private String state;
    private Boolean pep;

    /** INE, PASAPORTE, CEDULA_PROFESIONAL, FM2_FM3, MATRICULA_CONSULAR */
    private String documentType;
    private String documentNumber;
    private LocalDate documentExpiresAt;

    private String by;

    // legacy fields, accepted and ignored: cards are issued through /api/cards/issue
    private String cardLast4;
    @PositiveOrZero(message = "Initial deposit must be zero or positive")
    private BigDecimal initialDeposit;

    public boolean hasIdentity() {
        return curp != null && !curp.isBlank();
    }
}
