package bank.cardissuing.fraud.application;

import bank.cardissuing.card.domain.Card;
import bank.cardissuing.card.domain.Channel;
import bank.cardissuing.card.infrastructure.CardRepository;
import bank.cardissuing.common.exception.BusinessException;
import bank.cardissuing.common.exception.ResourceNotFoundException;
import bank.cardissuing.fraud.domain.AuthorizationAttempt;
import bank.cardissuing.fraud.domain.BlockedEntity;
import bank.cardissuing.fraud.domain.FraudAlert;
import bank.cardissuing.fraud.domain.StepUpChallenge;
import bank.cardissuing.fraud.infrastructure.AuthorizationAttemptRepository;
import bank.cardissuing.fraud.infrastructure.BlockedEntityRepository;
import bank.cardissuing.fraud.infrastructure.FraudAlertRepository;
import bank.cardissuing.fraud.infrastructure.StepUpChallengeRepository;
import bank.cardissuing.transaction.domain.AuthorizationRequest;
import bank.cardissuing.transaction.domain.AuthorizationResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The online risk engine. Scores every authorization from what the CMS already knows
 * -- the card's own history, the merchant's recent behaviour, the blocklists -- and
 * answers one of three things: approve, ask the cardholder to prove it is them, or
 * decline. Every answer says why, in rule codes for the analyst and in plain words
 * for the customer.
 *
 * <p>Rules are deterministic and cheap; nothing here calls the core. A step-up is only
 * offered where the channel can carry it (e-commerce); elsewhere a review-band score
 * is a decline, because a POS terminal cannot ask for a code.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FraudService {

    public enum Decision { APPROVE, STEP_UP, DECLINE }

    public record Assessment(int score, List<String> reasons, Decision decision, String challengeToken, String otpHint) {
        public static Assessment clean() { return new Assessment(0, List.of(), Decision.APPROVE, null, null); }
        public String reasonsCsv() { return String.join(",", reasons); }
    }

    private static final SecureRandom RANDOM = new SecureRandom();

    private final FraudSettings s;
    private final AuthorizationAttemptRepository attempts;
    private final FraudAlertRepository alerts;
    private final BlockedEntityRepository blocklist;
    private final StepUpChallengeRepository challenges;
    private final CardRepository cards;

    /** The industry antifraud connection; optional so the engine also runs without it (unit tests). */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    @lombok.Setter
    private bank.cardissuing.fraud.guild.application.GuildService guild;

    // ------------------------------------------------------------ scoring

    @Transactional
    public Assessment assess(Card card, AuthorizationRequest req) {
        LocalDateTime now = LocalDateTime.now();
        List<String> reasons = new ArrayList<>();
        int score = 0;
        Channel channel = req.channelOrDefault();

        // A verified step-up on the way in settles the question: the cardholder proved it is them.
        if (req.getStepUpToken() != null && !req.getStepUpToken().isBlank()) {
            return consumeStepUp(card, req, now);
        }

        // Blocklists: a hit is a decline, no arithmetic.
        if (req.getMerchantId() != null && blocklist.findByTypeAndValueAndActiveTrue(BlockedEntity.Type.MERCHANT_ID, req.getMerchantId()).isPresent()) {
            reasons.add("BLOCKLIST_MERCHANT"); score = 100;
        }
        if (req.getCountryCode() != null && blocklist.findByTypeAndValueAndActiveTrue(BlockedEntity.Type.COUNTRY, req.getCountryCode().toUpperCase()).isPresent()) {
            reasons.add("BLOCKLIST_COUNTRY"); score = 100;
        }
        if (blocklist.findByTypeAndValueAndActiveTrue(BlockedEntity.Type.CARD, String.valueOf(card.getId())).isPresent()) {
            reasons.add("BLOCKLIST_CARD"); score = 100;
        }
        // The guild's online list (SVL): asked at most once per window, cached, never blocking on an outage.
        if (score < 100 && guild != null) {
            try {
                if (guild.cardListed(card)) { reasons.add("GUILD_SVL_LISTED"); score = 100; }
            } catch (Exception e) {
                log.warn("Guild verification skipped for card {}: {}", card.getId(), e.getMessage());
            }
        }

        if (score < 100) {
            // Velocity: too many attempts on the card in a short window.
            long recent = attempts.countByCardAndCreatedAtAfter(card, now.minusMinutes(s.getVelocity().getWindowMin()));
            if (recent > s.getVelocity().getMax()) { reasons.add("VELOCITY_" + s.getVelocity().getWindowMin() + "M"); score += s.getVelocity().getWeight(); }

            // Repeated declines: card testing looks like this.
            long declined = attempts.countByCardAndApprovedFalseAndCreatedAtAfter(card, now.minusMinutes(s.getDeclines().getWindowMin()));
            if (declined >= s.getDeclines().getMax()) { reasons.add("DECLINES_" + s.getDeclines().getWindowMin() + "M"); score += s.getDeclines().getWeight(); }

            // Enumeration: one merchant, many different cards failing in minutes.
            if (req.getMerchantId() != null && enumerating(req.getMerchantId(), now)) {
                reasons.add("ENUMERATION"); score += s.getEnumeration().getWeight();
                raiseEnumerationAlert(req, now);
            }

            // Amount against the card's own habit.
            List<AuthorizationAttempt> history = attempts.findByCardAndApprovedTrueAndCreatedAtAfter(card, now.minusDays(s.getAmount().getHistoryDays()));
            if (history.size() >= s.getAmount().getMinHistory()) {
                BigDecimal avg = history.stream().map(AuthorizationAttempt::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add)
                        .divide(BigDecimal.valueOf(history.size()), 2, RoundingMode.HALF_UP);
                if (req.getAmount().compareTo(avg.multiply(BigDecimal.valueOf(s.getAmount().getAnomalyMultiplier()))) > 0) {
                    reasons.add("AMOUNT_ANOMALY"); score += s.getAmount().getAnomalyWeight();
                }
            } else if (history.isEmpty() && req.getAmount().compareTo(s.getAmount().getFirstTransactionMax()) > 0) {
                reasons.add("FIRST_TX_HIGH"); score += s.getAmount().getFirstTransactionWeight();
            }

            // Geography: a different country than the last approval, minutes apart.
            if (req.getCountryCode() != null) {
                attempts.findFirstByCardAndApprovedTrueOrderByCreatedAtDesc(card).ifPresent(last -> {
                    if (last.getCountryCode() != null && !last.getCountryCode().equalsIgnoreCase(req.getCountryCode())
                            && last.getCreatedAt() != null && last.getCreatedAt().isAfter(now.minusHours(s.getGeoJump().getHours()))) {
                        reasons.add("GEO_JUMP");
                    }
                });
                if (reasons.contains("GEO_JUMP")) score += s.getGeoJump().getWeight();
            }

            // A brand-new card spending big.
            if (card.getCreatedAt() != null && card.getCreatedAt().isAfter(now.minusHours(s.getNewCard().getHours()))
                    && req.getAmount().compareTo(s.getNewCard().getMax()) > 0) {
                reasons.add("NEW_CARD_HIGH"); score += s.getNewCard().getWeight();
            }
        }

        score = Math.min(score, 100);
        if (score >= s.getDeclineThreshold()) {
            alerts.save(new FraudAlert(card, req.getMerchantId(), req.getMerchantName(), FraudAlert.Type.DECLINED, score, String.join(",", reasons), req.getAmount()));
            return new Assessment(score, reasons, Decision.DECLINE, null, null);
        }
        if (score >= s.getReviewThreshold()) {
            if (channel != Channel.ECOMMERCE) {
                reasons.add("STEP_UP_UNAVAILABLE_" + channel);
                alerts.save(new FraudAlert(card, req.getMerchantId(), req.getMerchantName(), FraudAlert.Type.DECLINED, score, String.join(",", reasons), req.getAmount()));
                return new Assessment(score, reasons, Decision.DECLINE, null, null);
            }
            StepUpChallenge c = newChallenge(card, req, channel, now);
            alerts.save(new FraudAlert(card, req.getMerchantId(), req.getMerchantName(), FraudAlert.Type.STEP_UP, score, String.join(",", reasons), req.getAmount()));
            log.info("Step-up challenge {} for card {} ({}): score {} {}", c.getToken(), card.getId(), req.getMerchantName(), score, reasons);
            return new Assessment(score, reasons, Decision.STEP_UP, c.getToken(), s.getStepUp().isExposeOtp() ? c.getOtp() : null);
        }
        return new Assessment(score, reasons, Decision.APPROVE, null, null);
    }

    private boolean enumerating(String merchantId, LocalDateTime now) {
        LocalDateTime since = now.minusMinutes(s.getEnumeration().getWindowMin());
        long declinedAttempts = attempts.countByMerchantIdAndApprovedFalseAndCreatedAtAfter(merchantId, since);
        if (declinedAttempts < s.getEnumeration().getMinAttempts()) return false;
        return attempts.countDistinctCardsDeclinedAtMerchant(merchantId, since) >= s.getEnumeration().getMinCards();
    }

    private void raiseEnumerationAlert(AuthorizationRequest req, LocalDateTime now) {
        LocalDateTime since = now.minusMinutes(s.getEnumeration().getWindowMin());
        if (alerts.existsByMerchantIdAndTypeAndStatusAndCreatedAtAfter(req.getMerchantId(), FraudAlert.Type.ENUMERATION, FraudAlert.Status.OPEN, since)) return;
        alerts.save(new FraudAlert(null, req.getMerchantId(), req.getMerchantName(), FraudAlert.Type.ENUMERATION, s.getEnumeration().getWeight(),
                "ENUMERATION: many distinct cards declined at this merchant within " + s.getEnumeration().getWindowMin() + " min", null));
        log.warn("Enumeration suspected at merchant {} ({})", req.getMerchantId(), req.getMerchantName());
        if (guild != null) {
            try {
                guild.reportEnumeration(req.getMerchantId(), req.getMerchantName(), s.getEnumeration().getWeight(),
                        "Card-testing pattern: many distinct cards declined at this merchant within " + s.getEnumeration().getWindowMin() + " min");
            } catch (Exception e) {
                log.warn("Guild not notified of enumeration at {}: {}", req.getMerchantId(), e.getMessage());
            }
        }
    }

    // ------------------------------------------------------------ step-up

    private StepUpChallenge newChallenge(Card card, AuthorizationRequest req, Channel channel, LocalDateTime now) {
        String otp = String.format("%06d", RANDOM.nextInt(1_000_000));
        String token = UUID.randomUUID().toString();
        return challenges.save(new StepUpChallenge(token, card, req.getAmount(), req.getMerchantId(), channel.name(), otp,
                now.plusMinutes(s.getStepUp().getTtlMin())));
    }

    /** The cardholder answers the challenge. Wrong codes count; too many and it fails for good. */
    @Transactional
    public StepUpChallenge verify(String token, String otp) {
        StepUpChallenge c = challenges.findByToken(token).orElseThrow(() -> new ResourceNotFoundException("StepUpChallenge", "token", token));
        LocalDateTime now = LocalDateTime.now();
        if (c.getStatus() != StepUpChallenge.Status.PENDING) {
            throw new BusinessException("CHALLENGE_NOT_PENDING", "Challenge is " + c.getStatus(), HttpStatus.CONFLICT);
        }
        if (c.expired(now)) {
            c.setStatus(StepUpChallenge.Status.EXPIRED); challenges.save(c);
            throw new BusinessException("CHALLENGE_EXPIRED", "Challenge expired at " + c.getExpiresAt(), HttpStatus.GONE);
        }
        c.setAttempts(c.getAttempts() + 1);
        if (!c.getOtp().equals(otp == null ? "" : otp.trim())) {
            if (c.getAttempts() >= s.getStepUp().getMaxAttempts()) c.setStatus(StepUpChallenge.Status.FAILED);
            challenges.save(c);
            throw new BusinessException("CHALLENGE_WRONG_CODE", "Wrong code (" + c.getAttempts() + "/" + s.getStepUp().getMaxAttempts() + ")", HttpStatus.UNPROCESSABLE_ENTITY);
        }
        c.setStatus(StepUpChallenge.Status.VERIFIED);
        return challenges.save(c);
    }

    private Assessment consumeStepUp(Card card, AuthorizationRequest req, LocalDateTime now) {
        Optional<StepUpChallenge> found = challenges.findByToken(req.getStepUpToken());
        if (found.isEmpty()) return new Assessment(100, List.of("STEP_UP_INVALID"), Decision.DECLINE, null, null);
        StepUpChallenge c = found.get();
        boolean usable = c.getStatus() == StepUpChallenge.Status.VERIFIED && !c.expired(now)
                && c.getCard().getId().equals(card.getId()) && c.getAmount().compareTo(req.getAmount()) == 0;
        if (!usable) return new Assessment(100, List.of("STEP_UP_INVALID"), Decision.DECLINE, null, null);
        c.setStatus(StepUpChallenge.Status.CONSUMED);
        challenges.save(c);
        return new Assessment(0, List.of("STEP_UP_VERIFIED"), Decision.APPROVE, c.getToken(), null);
    }

    public StepUpChallenge challenge(String token) {
        return challenges.findByToken(token).orElseThrow(() -> new ResourceNotFoundException("StepUpChallenge", "token", token));
    }

    // ------------------------------------------------------------ memory

    /** Every decision is remembered; the rules above read this. */
    @Transactional
    public AuthorizationAttempt record(Card card, AuthorizationRequest req, AuthorizationResponse resp, Assessment a) {
        Assessment risk = a != null ? a : Assessment.clean();
        return attempts.save(new AuthorizationAttempt(card, req.getAmount(), req.getMerchantName(), req.getMerchantId(),
                req.channelOrDefault().name(), req.getCountryCode(), resp.getResponseCode(), resp.isApproved(),
                resp.getApprovalCode(), risk.score(), risk.reasonsCsv(), risk.decision() == Decision.STEP_UP));
    }

    public List<AuthorizationAttempt> history(Long cardId) {
        Card card = cards.findById(cardId).orElseThrow(() -> new ResourceNotFoundException("Card", "id", cardId));
        return attempts.findFirst100ByCardOrderByCreatedAtDesc(card);
    }

    /** A page of attempts: one card's, or everyone's when cardId is null. */
    public Page<AuthorizationAttempt> history(Long cardId, Pageable pageable) {
        if (cardId == null) return attempts.findAllByOrderByCreatedAtDesc(pageable);
        Card card = cards.findById(cardId).orElseThrow(() -> new ResourceNotFoundException("Card", "id", cardId));
        return attempts.findByCardOrderByCreatedAtDesc(card, pageable);
    }

    // ------------------------------------------------------------ analyst

    public List<FraudAlert> alerts(String status) {
        if (status == null || status.isBlank() || "ALL".equalsIgnoreCase(status)) return alerts.findAllByOrderByCreatedAtDesc();
        return alerts.findByStatusOrderByCreatedAtDesc(parseStatus(status));
    }

    public Page<FraudAlert> alerts(String status, Pageable pageable) {
        if (status == null || status.isBlank() || "ALL".equalsIgnoreCase(status)) return alerts.findAllByOrderByCreatedAtDesc(pageable);
        return alerts.findByStatusOrderByCreatedAtDesc(parseStatus(status), pageable);
    }

    private static FraudAlert.Status parseStatus(String status) {
        try { return FraudAlert.Status.valueOf(status.trim().toUpperCase()); }
        catch (IllegalArgumentException e) {
            throw new bank.cardissuing.common.exception.BusinessException("FRAUD_BAD_STATUS",
                    "status must be OPEN, REVIEWED, DISMISSED or ALL", org.springframework.http.HttpStatus.BAD_REQUEST);
        }
    }

    /** DISMISS, REVIEWED, BLOCK_MERCHANT or BLOCK_CARD. */
    @Transactional
    public FraudAlert review(Long id, String action, String note, String by) {
        FraudAlert a = alerts.findById(id).orElseThrow(() -> new ResourceNotFoundException("FraudAlert", "id", id));
        String who = by != null && !by.isBlank() ? by : "analyst";
        switch (action == null ? "" : action.toUpperCase()) {
            case "DISMISS" -> a.review(FraudAlert.Status.DISMISSED, "DISMISS", note, who);
            case "REVIEWED" -> a.review(FraudAlert.Status.REVIEWED, "REVIEWED", note, who);
            case "BLOCK_MERCHANT" -> {
                if (a.getMerchantId() == null) throw new BusinessException("ALERT_NO_MERCHANT", "This alert has no merchant to block", HttpStatus.UNPROCESSABLE_ENTITY);
                block(BlockedEntity.Type.MERCHANT_ID, a.getMerchantId(), "alert " + id + (note != null ? ": " + note : ""), who);
                a.review(FraudAlert.Status.REVIEWED, "BLOCK_MERCHANT", note, who);
            }
            case "BLOCK_CARD" -> {
                if (a.getCard() == null) throw new BusinessException("ALERT_NO_CARD", "This alert has no card to block", HttpStatus.UNPROCESSABLE_ENTITY);
                Card card = a.getCard();
                try { card.block(); cards.save(card); } catch (IllegalStateException e) { log.warn("Card {} not blocked: {}", card.getId(), e.getMessage()); }
                block(BlockedEntity.Type.CARD, String.valueOf(card.getId()), "alert " + id + (note != null ? ": " + note : ""), who);
                a.review(FraudAlert.Status.REVIEWED, "BLOCK_CARD", note, who);
            }
            default -> throw new BusinessException("ALERT_INVALID_ACTION", "action must be DISMISS, REVIEWED, BLOCK_MERCHANT or BLOCK_CARD", HttpStatus.BAD_REQUEST);
        }
        return alerts.save(a);
    }

    // ------------------------------------------------------------ blocklist

    public List<BlockedEntity> blocklist() { return blocklist.findByActiveTrueOrderByCreatedAtDesc(); }

    @Transactional
    public BlockedEntity block(BlockedEntity.Type type, String value, String reason, String by) {
        String v = type == BlockedEntity.Type.COUNTRY ? value.trim().toUpperCase() : value.trim();
        BlockedEntity e = blocklist.findByTypeAndValue(type, v).orElseGet(() -> new BlockedEntity(type, v, BlockedEntity.Source.INTERNAL, reason, by));
        e.setActive(true);
        if (reason != null) e.setReason(reason);
        if (by != null) e.setAddedBy(by);
        log.info("Blocklist: {} {} ({}) by {}", type, v, reason, by);
        return blocklist.save(e);
    }

    @Transactional
    public void unblock(Long id) {
        BlockedEntity e = blocklist.findById(id).orElseThrow(() -> new ResourceNotFoundException("BlockedEntity", "id", id));
        e.setActive(false);
        blocklist.save(e);
    }
}
