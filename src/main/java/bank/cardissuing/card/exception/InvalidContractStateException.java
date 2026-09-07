package bank.cardissuing.card.exception;

import bank.cardissuing.common.exception.BusinessException;
import org.springframework.http.HttpStatus;

public class InvalidContractStateException extends BusinessException {
    public InvalidContractStateException(String message) {
        super("INVALID_CONTRACT_STATE", message, HttpStatus.CONFLICT);
    }
}
