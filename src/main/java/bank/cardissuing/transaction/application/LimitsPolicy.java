package bank.cardissuing.transaction.application;

import bank.cardissuing.card.application.CardControlsService;
import bank.cardissuing.card.domain.Card;
import bank.cardissuing.card.domain.CardControls;
import bank.cardissuing.card.domain.CardProduct;
import bank.cardissuing.funds.domain.HoldStatus;
import bank.cardissuing.funds.infrastructure.AuthorizationHoldRepository;
import bank.cardissuing.transaction.domain.ResponseCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.Optional;

/**
 * Daily / weekly / monthly limits, measured over approved volume (held or captured).
 * A released or expired hold no longer counts. Windows are calendar based: today,
 * this ISO week (Monday start) and this month.
 *
 * <p>Each window takes the card's own limit when one is set, else the product's.
 * Zero means "no limit for this window".
 */
@Component
@RequiredArgsConstructor
public class LimitsPolicy {

    private static final EnumSet<HoldStatus> COUNTS = EnumSet.of(HoldStatus.HELD, HoldStatus.CAPTURED);

    private final AuthorizationHoldRepository holds;
    private final CardControlsService controls;

    /** Empty when the amount fits; otherwise the decline code with a reason. */
    public Optional<Breach> check(Card card, BigDecimal amount) {
        CardProduct product = card.getProduct();
        if (product == null) return Optional.empty();
        CardControls c = controls.forCard(card);

        LocalDate today = LocalDate.now();
        Optional<Breach> b;
        if ((b = breach(card, amount, pick(c.getDailyLimit(), product.getDailyLimit()), today.atStartOfDay(), "daily")).isPresent()) return b;
        if ((b = breach(card, amount, pick(c.getWeeklyLimit(), product.getWeeklyLimit()), today.with(DayOfWeek.MONDAY).atStartOfDay(), "weekly")).isPresent()) return b;
        if ((b = breach(card, amount, pick(c.getMonthlyLimit(), product.getMonthlyLimit()), today.withDayOfMonth(1).atStartOfDay(), "monthly")).isPresent()) return b;
        return Optional.empty();
    }

    private static BigDecimal pick(BigDecimal cardLimit, BigDecimal productLimit) {
        return cardLimit != null ? cardLimit : productLimit;
    }

    private Optional<Breach> breach(Card card, BigDecimal amount, BigDecimal limit, LocalDateTime since, String window) {
        if (limit == null || limit.signum() <= 0) return Optional.empty();
        BigDecimal used = holds.sumSince(card, COUNTS, since);
        if (used.add(amount).compareTo(limit) > 0) {
            return Optional.of(new Breach(ResponseCode.EXCEEDS_LIMIT,
                    window + " limit " + limit + " exceeded (used " + used + ", requested " + amount + ")"));
        }
        return Optional.empty();
    }
}
