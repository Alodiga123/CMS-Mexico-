package bank.cardissuing.common.security;

/**
 * The CMS's permissions as registered in the IAM under project EMISION_CMS.
 * READ: consult anything. OPERATE: cards, customers, controls, plastics, holds, ledger, reconciliation.
 * FRAUD: alerts, blocklists, step-up, guild. DISPUTES: open and drive chargebacks.
 * REPORTS: run and seal reports. ADMIN: products, promotions, HSM, settings.
 */
public final class Permissions {
    public static final String READ = "CMS:READ";
    public static final String OPERATE = "CMS:OPERATE";
    public static final String FRAUD = "CMS:FRAUD";
    public static final String DISPUTES = "CMS:DISPUTES";
    public static final String REPORTS = "CMS:REPORTS";
    public static final String ADMIN = "CMS:ADMIN";

    private Permissions() { }
}
