package bank.cardissuing.funds.application;

import bank.cardissuing.card.domain.Card;
import bank.cardissuing.card.domain.CardProduct;
import bank.cardissuing.card.domain.CardType;
import bank.cardissuing.card.domain.PaymentType;
import bank.cardissuing.funds.domain.AuthorizationHold;
import bank.cardissuing.funds.domain.FundsPort;
import bank.cardissuing.funds.domain.HoldStatus;
import bank.cardissuing.funds.infrastructure.AuthorizationHoldRepository;
import bank.cardissuing.ledger.application.LedgerService;
import bank.cardissuing.ledger.domain.LedgerAccount;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Prepaid cards: the money is the internal ledger. The hold is purely a CMS record
 * (shadow balance); the ledger is only written at capture, keeping it append-only.
 */
@Component
@RequiredArgsConstructor
public class PrepaidLedgerFunds implements FundsPort {

    private final LedgerService ledgerService;
    private final AuthorizationHoldRepository holds;

    @Override
    public boolean supports(CardProduct product) {
        return product.getCardType() == CardType.PREPAID
                || (product.getCardType() == CardType.DEBIT && product.getPaymentType() == PaymentType.PREPAID);
    }

    @Override
    public BigDecimal available(Card card) {
        LedgerAccount account = ledgerService.getLedgerAccountByCardId(card);
        BigDecimal balance = ledgerService.getBalance(account.getId());
        BigDecimal held = holds.sumByCardAndStatus(card, HoldStatus.HELD);
        return balance.subtract(held);
    }

    @Override
    public void hold(Card card, AuthorizationHold hold) {
        // Nothing to do in the ledger: the persisted hold row is the reservation.
    }

    @Override
    public void capture(Card card, AuthorizationHold hold) {
        LedgerAccount account = ledgerService.getLedgerAccountByCardId(card);
        ledgerService.debit(account.getId(), hold.getCapturedAmount(), hold.getApprovalCode(),
                "Capture " + hold.getMerchantName());
    }

    @Override
    public void release(Card card, AuthorizationHold hold) {
        // Nothing moved, nothing to give back.
    }
}
