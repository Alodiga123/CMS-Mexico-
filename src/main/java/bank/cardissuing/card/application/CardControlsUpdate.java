package bank.cardissuing.card.application;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Partial update: every field is optional, only the ones present are applied. */
public record CardControlsUpdate(
        Boolean posEnabled,
        Boolean atmEnabled,
        Boolean ecommerceEnabled,
        Boolean contactlessEnabled,
        Boolean internationalEnabled,
        LocalDate travelNoticeUntil,
        BigDecimal dailyLimit,
        BigDecimal weeklyLimit,
        BigDecimal monthlyLimit,
        BigDecimal perTransactionMax,
        /** Who is making the change, for the audit trail. */
        String performedBy
) { }
