package bank.cardissuing.customer.exception;

import bank.cardissuing.common.exception.BusinessException;
import org.springframework.http.HttpStatus;

public class InvalidCustomerDataException extends BusinessException {
    public InvalidCustomerDataException(String message) {
        super("INVALID_CUSTOMER_DATA", message, HttpStatus.BAD_REQUEST);
    }
}
