package bank.cardissuing.funds.application;

import bank.cardissuing.card.domain.Card;
import bank.cardissuing.card.domain.CardProduct;
import bank.cardissuing.card.domain.CardType;
import bank.cardissuing.funds.domain.AuthorizationHold;
import bank.cardissuing.funds.domain.FundsPort;
import bank.cardissuing.funds.domain.HoldStatus;
import bank.cardissuing.funds.infrastructure.AuthorizationHoldRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Credit cards: available = credit line minus everything held or captured.
 *
 * <p>The card's own limit wins over the product's. Repayments are not modelled yet:
 * captured amounts count as used until a statement/payment module reduces them, so
 * this is a revolving line without the "revolving" part. Good enough to authorize.
 */
@Component
@RequiredArgsConstructor
public class CreditLineFunds implements FundsPort {

    private final AuthorizationHoldRepository holds;

    @Override
    public boolean supports(CardProduct product) {
        return product.getCardType() == CardType.CREDIT;
    }

    @Override
    public BigDecimal available(Card card) {
        BigDecimal line = card.getCreditLimit() != null ? card.getCreditLimit()
                : card.getProduct().getCreditLimit() != null ? card.getProduct().getCreditLimit()
                : BigDecimal.ZERO;
        BigDecimal used = holds.sumByCardAndStatus(card, HoldStatus.HELD)
                .add(holds.sumByCardAndStatus(card, HoldStatus.CAPTURED));
        return line.subtract(used);
    }

    @Override
    public void hold(Card card, AuthorizationHold hold) { }

    @Override
    public void capture(Card card, AuthorizationHold hold) { }

    @Override
    public void release(Card card, AuthorizationHold hold) { }
}
