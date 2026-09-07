package bank.cardissuing.funds.domain;

/**
 * Lifecycle of an authorization hold. Authorizing never moves money: it reserves it.
 * Money moves on CAPTURE (clearing), and the reservation disappears on RELEASE
 * (reversal) or EXPIRED (hold aged out without a capture).
 */
public enum HoldStatus {
    HELD,
    CAPTURED,
    RELEASED,
    EXPIRED
}
