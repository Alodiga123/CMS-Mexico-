package bank.cardissuing.standin;

import bank.cardissuing.audit.application.AuditService;
import bank.cardissuing.card.domain.Card;
import bank.cardissuing.card.domain.Channel;
import bank.cardissuing.common.exception.BusinessException;
import bank.cardissuing.funds.application.FundsRouter;
import bank.cardissuing.funds.domain.AuthorizationHold;
import bank.cardissuing.funds.domain.FundsPort;
import bank.cardissuing.funds.infrastructure.AuthorizationHoldRepository;
import bank.cardissuing.funds.reconciliation.ReconciliationItem;
import bank.cardissuing.funds.reconciliation.ReconciliationItemRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * When the core cannot be asked, the CMS decides alone within the stand-in limits and
 * owes the core a reservation. The debt is settled by a job as soon as the core answers
 * again; a reservation the core refuses (the account had less than we assumed) becomes
 * a reconciliation item so someone chases it.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class StandInService {

    private final StandInSettings settings;
    private final AuthorizationHoldRepository holds;
    private final ReconciliationItemRepository items;
    private final FundsRouter router;
    private final AuditService audit;

    /** Why stand-in said no, if it did. */
    public record Refusal(String reason) { }

    /** May this operation be approved without the core? Empty means yes. */
    public Optional<Refusal> refuse(Card card, BigDecimal amount, Channel channel) {
        if (!settings.isEnabled()) return Optional.of(new Refusal("stand-in disabled"));
        if (channel != null && !settings.getChannels().contains(channel.name())) return Optional.of(new Refusal("channel " + channel + " not allowed in stand-in"));
        if (amount.compareTo(settings.getMaxAmount()) > 0) return Optional.of(new Refusal("amount " + amount + " over stand-in max " + settings.getMaxAmount()));
        LocalDateTime since = LocalDateTime.now().minusHours(24);
        BigDecimal used = holds.standInAmountSince(card, since);
        if (used.add(amount).compareTo(settings.getMaxDailyPerCard()) > 0) return Optional.of(new Refusal("stand-in daily total " + used.add(amount) + " over " + settings.getMaxDailyPerCard()));
        long count = holds.countByCardAndStandInTrueAndCreatedAtAfter(card, since);
        if (count >= settings.getMaxCountPerCardDaily()) return Optional.of(new Refusal("stand-in count " + count + " reached " + settings.getMaxCountPerCardDaily()));
        return Optional.empty();
    }

    /** Marks a hold as approved in stand-in: the core owes nothing yet, we owe the core. */
    public void markStandIn(AuthorizationHold hold) {
        hold.setStandIn(true);
        hold.setStandInPending(true);
        hold.setExternalRef(null);
    }

    /** Tries to place every pending stand-in reservation in the core. Returns how many settled. */
    public int settlePending() {
        int settled = 0;
        for (AuthorizationHold h : holds.findByStandInTrueAndStandInPendingTrueOrderByCreatedAtAsc()) {
            if (settleOne(h)) settled++;
        }
        return settled;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    protected boolean settleOne(AuthorizationHold h) {
        Card card = h.getCard();
        try {
            if (h.getStatus() != bank.cardissuing.funds.domain.HoldStatus.HELD) {
                // captured, released or expired while pending: nothing to reserve any more; the capture path already moved money or nothing is owed
                h.setStandInPending(false);
                h.setStandInSettledAt(LocalDateTime.now());
                holds.save(h);
                return true;
            }
            FundsPort port = router.forCard(card);
            port.hold(card, h);
            h.setStandInPending(false);
            h.setStandInSettledAt(LocalDateTime.now());
            holds.save(h);
            audit.log("STAND_IN_SETTLED", "AuthorizationHold", h.getApprovalCode(), "SYSTEM");
            log.info("Stand-in {} settled in the core ({})", h.getApprovalCode(), h.getExternalRef());
            return true;
        } catch (BusinessException e) {
            if ("CORE_UNAVAILABLE".equals(e.getErrorCode())) return false;   // still down: next round
            // the core is back and refused: the money was never there. Flag it and stop retrying.
            h.setStandInPending(false);
            h.setStandInSettledAt(LocalDateTime.now());
            holds.save(h);
            String key = "STAND_IN_REJECTED:" + h.getApprovalCode();
            if (items.findByItemKey(key).isEmpty()) {
                ReconciliationItem it = new ReconciliationItem();
                it.setItemKey(key);
                it.setType(ReconciliationItem.Type.STAND_IN_REJECTED);
                it.setAccountId(card.getExternalAccountId());
                it.setCardId(card.getId());
                it.setApprovalCode(h.getApprovalCode());
                it.setCmsAmount(h.getAmount());
                it.setCoreAmount(BigDecimal.ZERO);
                String detail = "Approved in stand-in; the core refused the reservation afterwards: " + e.getMessage();
                it.setDetail(detail.length() > 480 ? detail.substring(0, 480) : detail);
                it.setLastSeenAt(LocalDateTime.now());
                items.save(it);
            }
            audit.log("STAND_IN_REJECTED", "AuthorizationHold", h.getApprovalCode(), "SYSTEM");
            log.warn("Stand-in {} refused by the core: {}", h.getApprovalCode(), e.getMessage());
            return true;
        }
    }

    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", settings.isEnabled());
        m.put("maxAmount", settings.getMaxAmount());
        m.put("maxDailyPerCard", settings.getMaxDailyPerCard());
        m.put("maxCountPerCardDaily", settings.getMaxCountPerCardDaily());
        m.put("channels", settings.getChannels());
        List<AuthorizationHold> pending = holds.findByStandInTrueAndStandInPendingTrueOrderByCreatedAtAsc();
        m.put("pendingSettlement", pending.size());
        m.put("pendingAmount", pending.stream().map(AuthorizationHold::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add));
        return m;
    }
}
