package bank.cardissuing.customer.domain;

public enum KYCStatus {
    /** Registered, checks not run yet. */
    PENDING,
    /** Checks passed and no list hit: cards may be issued. */
    VERIFIED,
    /** Needs an analyst: politically exposed person, or the list provider did not answer. */
    REVIEW,
    /** Failed a hard check or matched a restricted list: no card until corrected and re-run. */
    REJECTED
}
