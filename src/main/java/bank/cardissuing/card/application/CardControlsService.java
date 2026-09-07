package bank.cardissuing.card.application;

import bank.cardissuing.audit.application.AuditService;
import bank.cardissuing.card.domain.Card;
import bank.cardissuing.card.domain.CardControls;
import bank.cardissuing.card.infrastructure.CardControlsRepository;
import bank.cardissuing.card.infrastructure.CardRepository;
import bank.cardissuing.common.exception.BusinessException;
import bank.cardissuing.common.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

@Slf4j
@Service
@RequiredArgsConstructor
public class CardControlsService {

    private final CardControlsRepository controls;
    private final CardRepository cards;
    private final AuditService audit;

    /** The card's controls, or defaults (all channels on, product limits) if none were ever set. */
    @Transactional(readOnly = true)
    public CardControls forCard(Card card) {
        return controls.findByCard(card).orElseGet(() -> new CardControls(card));
    }

    @Transactional(readOnly = true)
    public CardControls forCardId(Long cardId) {
        return forCard(loadCard(cardId));
    }

    @Transactional
    public CardControls update(Long cardId, CardControlsUpdate u) {
        Card card = loadCard(cardId);
        CardControls c = controls.findByCard(card).orElseGet(() -> new CardControls(card));

        if (u.posEnabled() != null) c.setPosEnabled(u.posEnabled());
        if (u.atmEnabled() != null) c.setAtmEnabled(u.atmEnabled());
        if (u.ecommerceEnabled() != null) c.setEcommerceEnabled(u.ecommerceEnabled());
        if (u.contactlessEnabled() != null) c.setContactlessEnabled(u.contactlessEnabled());
        if (u.internationalEnabled() != null) c.setInternationalEnabled(u.internationalEnabled());
        if (u.travelNoticeUntil() != null) c.setTravelNoticeUntil(u.travelNoticeUntil());
        if (u.dailyLimit() != null) c.setDailyLimit(nonNegative(u.dailyLimit(), "dailyLimit"));
        if (u.weeklyLimit() != null) c.setWeeklyLimit(nonNegative(u.weeklyLimit(), "weeklyLimit"));
        if (u.monthlyLimit() != null) c.setMonthlyLimit(nonNegative(u.monthlyLimit(), "monthlyLimit"));
        if (u.perTransactionMax() != null) c.setPerTransactionMax(nonNegative(u.perTransactionMax(), "perTransactionMax"));

        CardControls saved = controls.save(c);
        String by = u.performedBy() != null && !u.performedBy().isBlank() ? u.performedBy() : "SYSTEM";
        audit.log("UPDATE_CARD_CONTROLS", "Card", cardId.toString(), by);
        log.info("Card {} controls updated by {}: pos={} atm={} ecom={} ctl={} intl={} travelUntil={} limits d/w/m={}/{}/{} perTx={}",
                cardId, by, saved.isPosEnabled(), saved.isAtmEnabled(), saved.isEcommerceEnabled(),
                saved.isContactlessEnabled(), saved.isInternationalEnabled(), saved.getTravelNoticeUntil(),
                saved.getDailyLimit(), saved.getWeeklyLimit(), saved.getMonthlyLimit(), saved.getPerTransactionMax());
        return saved;
    }

    private Card loadCard(Long cardId) {
        return cards.findById(cardId).orElseThrow(() -> new ResourceNotFoundException("Card", "id", cardId));
    }

    /** Zero means "no limit for this window"; negatives are a mistake. */
    private static BigDecimal nonNegative(BigDecimal v, String field) {
        if (v.signum() < 0) {
            throw new BusinessException("INVALID_LIMIT", field + " cannot be negative", HttpStatus.BAD_REQUEST);
        }
        return v;
    }
}
