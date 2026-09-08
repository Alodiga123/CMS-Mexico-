package bank.cardissuing.funds.domain;

import bank.cardissuing.card.domain.Card;
import bank.cardissuing.card.domain.CardProduct;

import java.math.BigDecimal;

/**
 * Where the money of a card lives, and what the CMS can ask of it.
 *
 * <p>One implementation per product family. The authorizer never knows whether funds
 * are in the internal ledger, in the core banking system or in a credit line: it asks
 * the router for the port that supports the card's product and talks to that.
 */
public interface FundsPort {

    boolean supports(CardProduct product);

    /** Funds usable right now: balance (or line) minus everything already held. */
    BigDecimal available(Card card);

    /** Reserve funds. May set {@link AuthorizationHold#setExternalRef} when the core issues an id. */
    void hold(Card card, AuthorizationHold hold);

    /** Move the money for real. Called once at clearing. */
    void capture(Card card, AuthorizationHold hold);

    /** Give the reservation back without moving money. */
    void release(Card card, AuthorizationHold hold);

    /** Put money back on the card outside an authorization: a refund or a provisional credit. Returns a reference. */
    String credit(Card card, BigDecimal amount, String reference);

    /** Take money off the card outside an authorization: undoing a provisional credit. Returns a reference. */
    String debit(Card card, BigDecimal amount, String reference);
}
