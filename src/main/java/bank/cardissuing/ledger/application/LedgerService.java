package bank.cardissuing.ledger.application;

import bank.cardissuing.card.domain.Card;
import bank.cardissuing.ledger.domain.LedgerAccount;

import java.math.BigDecimal;

public interface LedgerService {

    LedgerAccount getLedgerAccount(Long ledgerAccountId);

    BigDecimal getBalance(Long ledgerAccountId);

    /** Take money out. Fails with InsufficientFundsException when the balance does not cover it. */
    void debit(Long ledgerAccountId, BigDecimal amount, String reference, String description);

    /** Put money in: top-ups, refunds, provisional credits. */
    void credit(Long ledgerAccountId, BigDecimal amount, String reference, String description);

    LedgerAccount getLedgerAccountByCardId(Card card);
}
