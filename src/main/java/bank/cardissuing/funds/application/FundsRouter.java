package bank.cardissuing.funds.application;

import bank.cardissuing.card.domain.Card;
import bank.cardissuing.common.exception.BusinessException;
import bank.cardissuing.funds.domain.FundsPort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.List;

/** Picks the funds source for a card from its product. One port must claim it. */
@Component
public class FundsRouter {

    private final List<FundsPort> ports;

    public FundsRouter(List<FundsPort> ports) {
        this.ports = ports;
    }

    public FundsPort forCard(Card card) {
        if (card.getProduct() == null) {
            throw new BusinessException("CARD_WITHOUT_PRODUCT",
                    "Card " + card.getId() + " has no product; cannot route funds", HttpStatus.UNPROCESSABLE_ENTITY);
        }
        return ports.stream()
                .filter(p -> p.supports(card.getProduct()))
                .findFirst()
                .orElseThrow(() -> new BusinessException("PRODUCT_NOT_SUPPORTED",
                        "No funds source for product " + card.getProduct().getProductCode()
                                + " (" + card.getProduct().getCardType() + "/" + card.getProduct().getPaymentType() + ")",
                        HttpStatus.UNPROCESSABLE_ENTITY));
    }
}
