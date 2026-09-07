package bank.cardissuing.card.exception;

import bank.cardissuing.common.exception.BusinessException;
import org.springframework.http.HttpStatus;

public class PinPolicyViolationException extends BusinessException {
    public PinPolicyViolationException(String message) {
        super("INVALID_PIN", message, HttpStatus.BAD_REQUEST);
    }
}
