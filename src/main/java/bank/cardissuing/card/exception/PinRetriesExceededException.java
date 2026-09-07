package bank.cardissuing.card.exception;

import bank.cardissuing.common.exception.BusinessException;
import org.springframework.http.HttpStatus;

public class PinRetriesExceededException extends BusinessException {
    public PinRetriesExceededException(Long cardId) {
        super("PIN_RETRIES_EXCEEDED",
                "Card " + cardId + " has been blocked after exceeding the maximum PIN attempts.",
                HttpStatus.LOCKED);
    }
}
