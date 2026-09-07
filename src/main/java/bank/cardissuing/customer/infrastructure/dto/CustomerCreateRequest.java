package bank.cardissuing.customer.infrastructure.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class CustomerCreateRequest {

    @NotBlank(message = "Full name is required")
    private String fullName;

    @NotBlank(message = "Phone number is required")
    private String phoneNumber;

    private String cardLast4;

    @PositiveOrZero(message = "Initial deposit must be zero or positive")
    private BigDecimal initialDeposit;
}
