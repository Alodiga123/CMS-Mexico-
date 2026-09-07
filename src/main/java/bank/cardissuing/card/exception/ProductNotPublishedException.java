package bank.cardissuing.card.exception;

import bank.cardissuing.common.exception.BusinessException;
import org.springframework.http.HttpStatus;

public class ProductNotPublishedException extends BusinessException {
    public ProductNotPublishedException(Long productId) {
        super("PRODUCT_NOT_PUBLISHED",
                "Card product " + productId + " is not published yet and cannot be issued to a customer.",
                HttpStatus.CONFLICT);
    }
}
