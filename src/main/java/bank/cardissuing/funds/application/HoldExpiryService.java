package bank.cardissuing.funds.application;

import bank.cardissuing.card.domain.Card;
import bank.cardissuing.funds.domain.AuthorizationHold;
import bank.cardissuing.funds.domain.HoldStatus;
import bank.cardissuing.funds.infrastructure.AuthorizationHoldRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Expires authorizations that were approved but never captured.
 *
 * <p>A hold that outlives its window gives the money back: the funds source releases
 * it (for core-backed debit that is a real releaseAmount in the core) and the row
 * becomes EXPIRED, so it stops counting against available funds and limits.
 *
 * <p>Each hold is expired in its own transaction. One that fails -- the core is down,
 * the reference is gone -- is logged and left HELD for the next run; the rest still
 * expire. Nothing here is ever retried inside the same run.
 */
@Slf4j
@Service
public class HoldExpiryService {

    private final AuthorizationHoldRepository holds;
    private final FundsRouter router;
    private final TransactionTemplate tx;

    public HoldExpiryService(AuthorizationHoldRepository holds, FundsRouter router,
                             PlatformTransactionManager transactionManager) {
        this.holds = holds;
        this.router = router;
        this.tx = new TransactionTemplate(transactionManager);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Expire everything due as of now. Returns how many were expired. */
    public int expireDue() {
        return expireDue(LocalDateTime.now());
    }

    public int expireDue(LocalDateTime now) {
        List<AuthorizationHold> due = holds.findByStatusAndExpiresAtBefore(HoldStatus.HELD, now);
        if (due.isEmpty()) return 0;
        int expired = 0;
        for (AuthorizationHold h : due) {
            if (expireOne(h.getId())) expired++;
        }
        log.info("Hold expiry: {} due, {} expired, {} left for next run", due.size(), expired, due.size() - expired);
        return expired;
    }

    boolean expireOne(Long holdId) {
        try {
            return Boolean.TRUE.equals(tx.execute(status -> {
                AuthorizationHold hold = holds.findById(holdId).orElse(null);
                // Re-check inside the transaction: it may have been captured or reversed meanwhile.
                if (hold == null || hold.getStatus() != HoldStatus.HELD) return false;
                Card card = hold.getCard();
                router.forCard(card).release(card, hold);
                hold.expire();
                holds.save(hold);
                log.info("Expired hold {} ({} on card {})", hold.getApprovalCode(), hold.getAmount(), card.getId());
                return true;
            }));
        } catch (RuntimeException e) {
            log.warn("Hold {} could not be expired, will retry next run: {}", holdId, e.getMessage());
            return false;
        }
    }
}
