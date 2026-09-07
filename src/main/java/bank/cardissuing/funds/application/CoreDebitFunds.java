package bank.cardissuing.funds.application;

import bank.cardissuing.card.domain.Card;
import bank.cardissuing.card.domain.CardProduct;
import bank.cardissuing.card.domain.CardType;
import bank.cardissuing.card.domain.PaymentType;
import bank.cardissuing.common.exception.BusinessException;
import bank.cardissuing.funds.core.CoreBankingClient;
import bank.cardissuing.funds.domain.AuthorizationHold;
import bank.cardissuing.funds.domain.FundsPort;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Debit cards whose balance is the customer's account in the core. The core owns the
 * money and the reservation; the CMS keeps the card, the limits and a mirror of the hold.
 */
@Component
@RequiredArgsConstructor
public class CoreDebitFunds implements FundsPort {

    private final CoreBankingClient core;

    @Override
    public boolean supports(CardProduct product) {
        return product.getCardType() == CardType.DEBIT && product.getPaymentType() == PaymentType.POSTPAID;
    }

    @Override
    public BigDecimal available(Card card) {
        return core.availableBalance(accountOf(card));
    }

    @Override
    public void hold(Card card, AuthorizationHold hold) {
        String ref = core.holdAmount(accountOf(card), hold.getAmount(), hold.getApprovalCode());
        hold.setExternalRef(ref);
    }

    @Override
    public void capture(Card card, AuthorizationHold hold) {
        String account = accountOf(card);
        // Fineract has no "capture a hold": release the reservation, then debit for real.
        if (hold.getExternalRef() != null) {
            core.releaseHold(account, hold.getExternalRef());
        }
        core.withdraw(account, hold.getCapturedAmount(), hold.getApprovalCode());
    }

    @Override
    public void release(Card card, AuthorizationHold hold) {
        if (hold.getExternalRef() != null) {
            core.releaseHold(accountOf(card), hold.getExternalRef());
        }
    }

    private static String accountOf(Card card) {
        String id = card.getExternalAccountId();
        if (id == null || id.isBlank()) {
            throw new BusinessException("CARD_NOT_LINKED_TO_CORE",
                    "Card " + card.getId() + " has no core account linked", HttpStatus.UNPROCESSABLE_ENTITY);
        }
        return id;
    }
}
