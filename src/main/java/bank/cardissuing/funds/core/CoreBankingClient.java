package bank.cardissuing.funds.core;

import java.math.BigDecimal;

/**
 * The contract with the core banking system, reduced to what an authorizer needs.
 *
 * <p>Account ids are the core's own (for Mifos/Fineract, the savings account id).
 * References are opaque strings the core returns and later expects back.
 */
public interface CoreBankingClient {

    BigDecimal availableBalance(String accountId);

    /** Reserve funds on the account. Returns the core's reference for the hold. */
    String holdAmount(String accountId, BigDecimal amount, String reference);

    /** Undo a reservation. */
    void releaseHold(String accountId, String holdRef);

    /** Debit for real. Returns the core's transaction reference. */
    String withdraw(String accountId, BigDecimal amount, String reference);

    /** Credit for real (refunds, top-ups). Returns the core's transaction reference. */
    String deposit(String accountId, BigDecimal amount, String reference);
}
