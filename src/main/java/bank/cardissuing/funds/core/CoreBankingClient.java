package bank.cardissuing.funds.core;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * The contract with the core banking system, reduced to what the CMS needs.
 *
 * <p>Account and client ids are the core's own (for Mifos/Fineract, savings account
 * and client ids). References are opaque strings the core returns and later expects.
 */
public interface CoreBankingClient {

    // ---- money: what the authorizer uses ----

    BigDecimal availableBalance(String accountId);

    /** Reserve funds on the account. Returns the core's reference for the hold. */
    String holdAmount(String accountId, BigDecimal amount, String reference);

    /** Undo a reservation. */
    void releaseHold(String accountId, String holdRef);

    /** Debit for real. Returns the core's transaction reference. */
    String withdraw(String accountId, BigDecimal amount, String reference);

    /** Credit for real (refunds, top-ups). Returns the core's transaction reference. */
    String deposit(String accountId, BigDecimal amount, String reference);

    // ---- accounts: what card issuance uses ----

    /** The core's client id for one of ours, if it was ever created there. */
    Optional<String> findClientByExternalId(String externalId);

    /** Create and activate a client. Returns the core's client id. */
    String createClient(String firstName, String lastName, String externalId);

    /** Open, approve and activate a savings account for the client. Returns the account id. */
    String openSavingsAccount(String clientId, String externalId);

    /** True when the account exists and can transact. */
    boolean accountIsActive(String accountId);
}
