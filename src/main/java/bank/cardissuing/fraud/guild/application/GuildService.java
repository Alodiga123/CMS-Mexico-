package bank.cardissuing.fraud.guild.application;

import bank.cardissuing.audit.application.AuditService;
import bank.cardissuing.card.domain.Card;
import bank.cardissuing.card.infrastructure.CardRepository;
import bank.cardissuing.common.exception.BusinessException;
import bank.cardissuing.fraud.domain.BlockedEntity;
import bank.cardissuing.fraud.guild.application.GuildClient.Hit;
import bank.cardissuing.fraud.guild.application.GuildClient.Inbound;
import bank.cardissuing.fraud.guild.application.GuildClient.Outbound;
import bank.cardissuing.fraud.guild.domain.GuildAlert;
import bank.cardissuing.fraud.guild.domain.GuildAlert.Direction;
import bank.cardissuing.fraud.guild.domain.GuildAlert.Status;
import bank.cardissuing.fraud.guild.domain.GuildAlert.Type;
import bank.cardissuing.fraud.guild.domain.GuildVerification;
import bank.cardissuing.fraud.guild.domain.GuildVerification.Subject;
import bank.cardissuing.fraud.guild.infrastructure.GuildAlertRepository;
import bank.cardissuing.fraud.guild.infrastructure.GuildVerificationRepository;
import bank.cardissuing.fraud.infrastructure.BlockedEntityRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The CMS's side of the industry antifraud connection.
 *
 * Outbound: enumeration attacks, confirmed fraud and suspicious merchants go to the
 * guild's alert system (SNA); chargebacks are announced early (SPC). Everything is
 * written to an outbox first and sent by a job, so a guild outage never touches the
 * authorizer's latency and nothing is lost.
 *
 * Inbound: alerts the guild sends us are stored, acted on (a compromised card is
 * blocked, a suspicious merchant blocklisted) and given a deadline; past it the loss
 * is assumed to be ours, as the guild's rules say.
 *
 * Verification: the guild's online list (SVL) is asked about a card or merchant at
 * most once per window; a hit lands in the local blocklist so the authorizer declines
 * without asking again.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class GuildService {

    private static final Set<Status> OPEN_OUTBOUND = EnumSet.of(Status.PENDING_SEND, Status.SENT, Status.FAILED);
    private static final Set<Status> ANSWERABLE = EnumSet.of(Status.RECEIVED, Status.IN_REVIEW, Status.SENT);

    private final GuildClient client;
    private final GuildSettings settings;
    private final GuildAlertRepository alerts;
    private final GuildVerificationRepository verifications;
    private final BlockedEntityRepository blocklist;
    private final CardRepository cards;
    private final AuditService audit;

    private volatile LocalDateTime lastInboundPoll;

    // ------------------------------------------------------------ outbound

    /** Called by the fraud engine when it sees a card-testing attack on a merchant. */
    @Transactional
    public GuildAlert reportEnumeration(String merchantId, String merchantName, int riskScore, String description) {
        if (merchantId != null && alerts.existsByDirectionAndTypeAndMerchantIdAndStatusInAndCreatedAtAfter(
                Direction.OUTBOUND, Type.ENUMERATION, merchantId, OPEN_OUTBOUND, LocalDateTime.now().minusHours(24))) {
            return null; // already told them today
        }
        GuildAlert a = GuildAlert.outbound(Type.ENUMERATION, "FRAUD_ENGINE", "SYSTEM");
        a.setMerchantId(merchantId);
        a.setMerchantName(merchantName);
        a.setAmount(null);
        a.setDescription(description);
        a.setRespondBy(LocalDate.now().plusDays(settings.getEnumerationResponseDays()));
        a = alerts.save(a);
        audit.log("GUILD_ALERT_QUEUED", "GuildAlert", a.getId().toString(), "SYSTEM");
        log.warn("Guild alert #{} queued: enumeration at merchant {} ({})", a.getId(), merchantId, merchantName);
        return a;
    }

    /** An analyst raises an alert by hand: confirmed fraud on a card, or a merchant to warn about. */
    @Transactional
    public GuildAlert raise(Type type, Long cardId, String merchantId, String merchantName, String description, BigDecimal amount, String by) {
        if (type == null || type == Type.CHARGEBACK_PREVENTION) throw new BusinessException("GUILD_BAD_TYPE", "type must be CONFIRMED_FRAUD, COMPROMISED_CARD, SUSPICIOUS_MERCHANT, ENUMERATION or OTHER", HttpStatus.BAD_REQUEST);
        if ((type == Type.CONFIRMED_FRAUD || type == Type.COMPROMISED_CARD) && cardId == null) throw new BusinessException("GUILD_CARD_REQUIRED", "cardId is required for " + type, HttpStatus.BAD_REQUEST);
        if ((type == Type.SUSPICIOUS_MERCHANT || type == Type.ENUMERATION) && (merchantId == null || merchantId.isBlank())) throw new BusinessException("GUILD_MERCHANT_REQUIRED", "merchantId is required for " + type, HttpStatus.BAD_REQUEST);
        GuildAlert a = GuildAlert.outbound(type, "MANUAL", by != null ? by : "API");
        if (cardId != null) {
            Card c = cards.findById(cardId).orElseThrow(() -> new BusinessException("CARD_NOT_FOUND", "Card " + cardId + " not found", HttpStatus.NOT_FOUND));
            a.setCardId(c.getId());
            a.setBin(c.getProduct() != null ? c.getProduct().getBin() : null);
            a.setLast4(c.getLast4());
        }
        a.setMerchantId(merchantId);
        a.setMerchantName(merchantName);
        a.setDescription(description);
        a.setAmount(amount);
        a.setRespondBy(addBusinessDays(LocalDate.now(), settings.getCloseBusinessDays()));
        a = alerts.save(a);
        audit.log("GUILD_ALERT_QUEUED", "GuildAlert", a.getId().toString(), a.getRaisedBy());
        return a;
    }

    /** Called by disputes when a chargeback goes out: give the guild the early notice (SPC). */
    @Transactional
    public GuildAlert preventChargeback(Long disputeId, Card card, BigDecimal amount, String reasonCode, String approvalCode, String by) {
        GuildAlert a = GuildAlert.outbound(Type.CHARGEBACK_PREVENTION, "DISPUTE:" + disputeId, by != null ? by : "SYSTEM");
        a.setCardId(card.getId());
        a.setBin(card.getProduct() != null ? card.getProduct().getBin() : null);
        a.setLast4(card.getLast4());
        a.setAmount(amount);
        a.setDescription("Chargeback reason " + reasonCode + " on authorization " + approvalCode);
        a = alerts.save(a);
        return a;
    }

    /** Sends what is waiting; each alert in its own transaction so one failure does not hold the rest. */
    public int flushOutbox() {
        int sent = 0;
        for (GuildAlert a : alerts.findByStatusInOrderByCreatedAtAsc(EnumSet.of(Status.PENDING_SEND, Status.FAILED))) {
            if (a.getStatus() == Status.FAILED && a.getAttempts() >= settings.getOutboxMaxAttempts()) continue;
            if (trySend(a)) sent++;
        }
        return sent;
    }

    @Transactional
    public GuildAlert sendNow(Long id) {
        GuildAlert a = get(id);
        if (a.getDirection() != Direction.OUTBOUND || !(a.getStatus() == Status.PENDING_SEND || a.getStatus() == Status.FAILED)) {
            throw new BusinessException("GUILD_ALERT_NOT_SENDABLE", "Alert " + id + " is " + a.getDirection() + "/" + a.getStatus(), HttpStatus.CONFLICT);
        }
        trySend(a);
        return a;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    protected boolean trySend(GuildAlert a) {
        try {
            String folio = client.send(new Outbound("CMS-" + a.getId(), a.getType().name(), a.getBin(), a.getLast4(), a.getMerchantId(),
                    a.getMerchantName(), a.getDescription(), a.getAmount(), a.getSourceRef()));
            a.sent(folio, LocalDateTime.now());
            alerts.save(a);
            audit.log("GUILD_ALERT_SENT", "GuildAlert", a.getId().toString(), "SYSTEM");
            log.info("Guild alert #{} ({}) sent, folio {}", a.getId(), a.getType(), folio);
            return true;
        } catch (Exception e) {
            a.failed(e.getMessage());
            alerts.save(a);
            log.warn("Guild alert #{} not sent (attempt {}): {}", a.getId(), a.getAttempts(), e.getMessage());
            return false;
        }
    }

    // ------------------------------------------------------------ verification (SVL)

    /** Is this card on the guild's list? Cached; fail-open (or closed) per settings when the guild is unreachable. */
    public boolean cardListed(Card card) {
        if (!settings.isVerifyOnAuthorization()) return false;
        return verifyCard(card).isListed();
    }

    @Transactional
    public GuildVerification verifyCard(Card card) {
        String bin = card.getProduct() != null ? card.getProduct().getBin() : null;
        return verify(Subject.CARD, String.valueOf(card.getId()), () -> client.verifyCard(bin, card.getLast4()), v -> {
            blocklistIfListed(BlockedEntity.Type.CARD, String.valueOf(card.getId()), v);
            if (v.isListed()) {
                GuildAlert a = GuildAlert.inbound(Type.COMPROMISED_CARD, v.getFolio(), LocalDateTime.now());
                a.setCardId(card.getId()); a.setBin(bin); a.setLast4(card.getLast4());
                a.setDescription("SVL: " + v.getReason());
                a.setRespondBy(addBusinessDays(LocalDate.now(), settings.getCloseBusinessDays()));
                a.setStatus(Status.IN_REVIEW);
                a.setAutoAction("CARD_BLOCKLISTED");
                if (alerts.findByGuildFolio(v.getFolio()).isEmpty()) alerts.save(a);
            }
        });
    }

    @Transactional
    public GuildVerification verifyMerchant(String merchantId) {
        return verify(Subject.MERCHANT, merchantId, () -> client.verifyMerchant(merchantId),
                v -> blocklistIfListed(BlockedEntity.Type.MERCHANT_ID, merchantId, v));
    }

    private GuildVerification verify(Subject type, String subject, java.util.function.Supplier<Hit> ask, java.util.function.Consumer<GuildVerification> onAnswer) {
        LocalDateTime now = LocalDateTime.now();
        GuildVerification v = verifications.findBySubjectTypeAndSubject(type, subject).orElseGet(() -> new GuildVerification(type, subject));
        if (v.fresh(now)) return v;
        try {
            Hit h = ask.get();
            v.setListed(h.listed());
            v.setFolio(h.folio());
            v.setReason(h.reason());
            v.setDegraded(false);
        } catch (Exception e) {
            v.setListed(!settings.isFailOpen());
            v.setFolio(null);
            v.setReason("guild unreachable: " + e.getMessage());
            v.setDegraded(true);
            log.warn("Guild SVL unreachable for {} {} ({}): {}", type, subject, settings.isFailOpen() ? "fail-open" : "fail-closed", e.getMessage());
        }
        v.setCheckedAt(now);
        // a degraded answer is retried soon; a real one holds for the window
        v.setValidUntil(v.isDegraded() ? now.plusMinutes(5) : now.plusHours(settings.getVerificationCacheHours()));
        v = verifications.save(v);
        if (!v.isDegraded()) onAnswer.accept(v);
        return v;
    }

    private void blocklistIfListed(BlockedEntity.Type type, String value, GuildVerification v) {
        if (!v.isListed()) return;
        BlockedEntity e = blocklist.findByTypeAndValue(type, value)
                .orElseGet(() -> new BlockedEntity(type, value, BlockedEntity.Source.EXTERNAL, null, "GUILD"));
        e.setActive(true);
        e.setSource(BlockedEntity.Source.EXTERNAL);
        e.setReason("GUILD SVL " + v.getFolio() + (v.getReason() != null ? ": " + v.getReason() : ""));
        blocklist.save(e);
        audit.log("BLOCKLIST_FROM_GUILD", type.name(), value, "GUILD");
    }

    // ------------------------------------------------------------ inbound (SNA)

    /** Fetches what the guild has for us since the last poll and acts on it. Returns how many were new. */
    @Transactional
    public int pollInbound() {
        LocalDateTime since = lastInboundPoll;
        if (since == null) {
            since = alerts.findTopByDirectionOrderByReceivedAtDesc(Direction.INBOUND).map(GuildAlert::getReceivedAt).orElse(null);
        }
        List<Inbound> incoming;
        try {
            incoming = client.fetchInbound(since);
        } catch (Exception e) {
            log.warn("Guild inbound poll failed: {}", e.getMessage());
            return 0;
        }
        int created = 0;
        LocalDateTime newest = since;
        for (Inbound in : incoming) {
            if (in.folio() == null || alerts.findByGuildFolio(in.folio()).isPresent()) continue;
            Type type;
            try { type = Type.valueOf(in.type()); } catch (Exception e) { type = Type.OTHER; }
            GuildAlert a = GuildAlert.inbound(type, in.folio(), in.issuedAt() != null ? in.issuedAt() : LocalDateTime.now());
            a.setBin(in.bin()); a.setLast4(in.last4());
            a.setMerchantId(in.merchantId()); a.setMerchantName(in.merchantName());
            a.setDescription((in.source() != null ? "[" + in.source() + "] " : "") + in.description());
            a.setRespondBy(addBusinessDays(LocalDate.now(), settings.getCloseBusinessDays()));
            if (settings.isAutoActOnInbound()) act(a);
            a.setStatus(Status.IN_REVIEW);
            a = alerts.save(a);
            audit.log("GUILD_ALERT_RECEIVED", "GuildAlert", a.getId().toString(), "GUILD");
            created++;
            if (newest == null || a.getReceivedAt().isAfter(newest)) newest = a.getReceivedAt();
        }
        lastInboundPoll = newest != null ? newest : LocalDateTime.now();
        return created;
    }

    /** What the CMS does on its own with an inbound alert. */
    private void act(GuildAlert a) {
        switch (a.getType()) {
            case COMPROMISED_CARD, CONFIRMED_FRAUD -> {
                List<Card> hits = new ArrayList<>();
                if (a.getLast4() != null) {
                    for (Card c : cards.findByLast4(a.getLast4())) {
                        String bin = c.getProduct() != null ? c.getProduct().getBin() : null;
                        if (a.getBin() == null || a.getBin().equals(bin)) hits.add(c);
                    }
                }
                if (hits.isEmpty()) { a.setAutoAction("NO_MATCHING_CARD"); return; }
                for (Card c : hits) {
                    if (a.getCardId() == null) a.setCardId(c.getId());
                    try { c.block(); cards.save(c); } catch (IllegalStateException e) { log.warn("Card {} not blocked on guild alert: {}", c.getId(), e.getMessage()); }
                    BlockedEntity e = blocklist.findByTypeAndValue(BlockedEntity.Type.CARD, String.valueOf(c.getId()))
                            .orElseGet(() -> new BlockedEntity(BlockedEntity.Type.CARD, String.valueOf(c.getId()), BlockedEntity.Source.EXTERNAL, null, "GUILD"));
                    e.setActive(true); e.setSource(BlockedEntity.Source.EXTERNAL); e.setReason("GUILD SNA " + a.getGuildFolio());
                    blocklist.save(e);
                    audit.log("BLOCK_CARD_GUILD_ALERT", "Card", String.valueOf(c.getId()), "GUILD");
                }
                a.setAutoAction("CARD_BLOCKED");
            }
            case SUSPICIOUS_MERCHANT, ENUMERATION -> {
                if (a.getMerchantId() == null) { a.setAutoAction("NO_MERCHANT"); return; }
                BlockedEntity e = blocklist.findByTypeAndValue(BlockedEntity.Type.MERCHANT_ID, a.getMerchantId())
                        .orElseGet(() -> new BlockedEntity(BlockedEntity.Type.MERCHANT_ID, a.getMerchantId(), BlockedEntity.Source.EXTERNAL, null, "GUILD"));
                e.setActive(true); e.setSource(BlockedEntity.Source.EXTERNAL); e.setReason("GUILD SNA " + a.getGuildFolio());
                blocklist.save(e);
                audit.log("BLOCKLIST_FROM_GUILD", "MERCHANT_ID", a.getMerchantId(), "GUILD");
                a.setAutoAction("MERCHANT_BLOCKLISTED");
            }
            default -> a.setAutoAction("NONE");
        }
    }

    // ------------------------------------------------------------ lifecycle

    @Transactional
    public GuildAlert close(Long id, String resolution, String by) {
        GuildAlert a = get(id);
        if (!a.getStatus().open()) throw new BusinessException("GUILD_ALERT_CLOSED", "Alert " + id + " is already " + a.getStatus(), HttpStatus.CONFLICT);
        if (a.getDirection() == Direction.OUTBOUND && a.getStatus() != Status.SENT) throw new BusinessException("GUILD_ALERT_NOT_SENT", "Alert " + id + " was never acknowledged by the guild", HttpStatus.CONFLICT);
        a.close(resolution, by != null ? by : "API", LocalDateTime.now());
        audit.log("GUILD_ALERT_CLOSED", "GuildAlert", a.getId().toString(), a.getClosedBy());
        return alerts.save(a);
    }

    /** Anything still open past its deadline: the guild assumes the loss is ours. */
    @Transactional
    public int expireOverdue() {
        int n = 0;
        for (GuildAlert a : alerts.findByStatusInAndRespondByBefore(ANSWERABLE, LocalDate.now())) {
            a.expire(LocalDateTime.now());
            alerts.save(a);
            audit.log("GUILD_ALERT_EXPIRED", "GuildAlert", a.getId().toString(), "SYSTEM");
            log.warn("Guild alert #{} ({}) expired: deadline {} passed, loss assumed", a.getId(), a.getType(), a.getRespondBy());
            n++;
        }
        return n;
    }

    public GuildAlert get(Long id) {
        return alerts.findById(id).orElseThrow(() -> new BusinessException("GUILD_ALERT_NOT_FOUND", "Guild alert " + id + " not found", HttpStatus.NOT_FOUND));
    }

    public List<GuildAlert> list(Direction direction, Status status) {
        if (direction != null && status != null) return alerts.findTop200ByDirectionAndStatusOrderByCreatedAtDesc(direction, status);
        if (direction != null) return alerts.findTop200ByDirectionOrderByCreatedAtDesc(direction);
        if (status != null) return alerts.findTop200ByStatusOrderByCreatedAtDesc(status);
        return alerts.findTop200ByOrderByCreatedAtDesc();
    }

    public List<GuildVerification> verifications() { return verifications.findTop200ByOrderByCheckedAtDesc(); }

    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("mode", client.mode());
        m.put("participantId", settings.getParticipantId());
        GuildClient.Health h;
        try { h = client.health(); } catch (Exception e) { h = new GuildClient.Health(false, e.getMessage()); }
        m.put("up", h.up());
        m.put("detail", h.detail());
        m.put("failOpen", settings.isFailOpen());
        m.put("verifyOnAuthorization", settings.isVerifyOnAuthorization());
        m.put("pendingSend", alerts.countByStatus(Status.PENDING_SEND));
        m.put("failed", alerts.countByStatus(Status.FAILED));
        m.put("inReview", alerts.countByStatus(Status.IN_REVIEW) + alerts.countByStatus(Status.RECEIVED));
        m.put("dueSoon", alerts.countByStatusInAndRespondByBefore(ANSWERABLE, LocalDate.now().plusDays(3)));
        m.put("expired", alerts.countByStatus(Status.EXPIRED));
        m.put("lastInboundPoll", lastInboundPoll);
        m.put("closeBusinessDays", settings.getCloseBusinessDays());
        m.put("enumerationResponseDays", settings.getEnumerationResponseDays());
        return m;
    }

    /** Weekends skipped; public holidays are the guild's calendar and are not modelled here. */
    static LocalDate addBusinessDays(LocalDate from, int days) {
        LocalDate d = from;
        int added = 0;
        while (added < days) {
            d = d.plusDays(1);
            if (d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY) added++;
        }
        return d;
    }
}
