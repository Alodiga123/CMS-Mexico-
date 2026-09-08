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
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Debit cards whose balance is the customer's account in the core. The core owns the
 * money and the reservation; the CMS keeps the card, the limits and a mirror of the hold.
 */
@Slf4j
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

    /**
     * Fineract has no "capture a hold", so capture is two calls. Order matters:
     * withdraw first, then release. If the withdrawal fails nothing has changed in
     * the core and the hold stays consistent on both sides. If the release fails after
     * a successful withdrawal the account is merely over-reserved until reconciliation,
     * which is the safe side -- so that release is best-effort and never undoes the
     * capture. It also tolerates a hold the core no longer knows about.
     */
    @Override
    public void capture(Card card, AuthorizationHold hold) {
        String account = accountOf(card);
        String txRef = core.withdraw(account, hold.getCapturedAmount(), hold.getApprovalCode());
        hold.setCaptureRef(txRef);
        log.info("Captured {} on core account {} (core tx {})", hold.getCapturedAmount(), account, txRef);
        if (hold.getExternalRef() != null) {
            try {
                core.releaseHold(account, hold.getExternalRef());
            } catch (BusinessException e) {
                log.warn("Hold {} on core account {} could not be released after capture ({}): "
                         + "left for reconciliation", hold.getExternalRef(), account, e.getMessage());
            }
        }
    }

    /**
     * Releasing is idempotent from the CMS's point of view: if the core already let the
     * money go (a release done behind our back, reconciled as HOLD_MISSING_IN_CORE),
     * marking the hold RELEASED here is exactly right, so a core refusal is logged, not raised.
     */
    @Override
    public void release(Card card, AuthorizationHold hold) {
        if (hold.getExternalRef() == null) return;
        try {
            core.releaseHold(accountOf(card), hold.getExternalRef());
        } catch (BusinessException e) {
            log.warn("Hold {} on core account {} could not be released ({}): treating as already released",
                    hold.getExternalRef(), card.getExternalAccountId(), e.getMessage());
        }
    }

    @Override
    public String credit(Card card, BigDecimal amount, String reference) {
        return core.deposit(accountOf(card), amount, reference);
    }

    @Override
    public String debit(Card card, BigDecimal amount, String reference) {
        return core.withdraw(accountOf(card), amount, reference);
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
