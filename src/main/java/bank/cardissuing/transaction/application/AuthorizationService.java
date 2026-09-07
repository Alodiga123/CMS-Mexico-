package bank.cardissuing.transaction.application;

import bank.cardissuing.funds.domain.AuthorizationHold;
import bank.cardissuing.transaction.domain.AuthorizationRequest;
import bank.cardissuing.transaction.domain.AuthorizationResponse;

import java.math.BigDecimal;

public interface AuthorizationService {

    /** Decide and, if approved, reserve. {@code idempotencyKey} may be null. */
    AuthorizationResponse authorize(AuthorizationRequest request, String idempotencyKey);

    /** Settle a held authorization. {@code amount} null means the full held amount. */
    AuthorizationHold capture(String approvalCode, BigDecimal amount);

    /** Give a held authorization back (reversal). */
    AuthorizationHold reverse(String approvalCode);

    AuthorizationHold get(String approvalCode);
}
