package bank.cardissuing.transaction.application;

import bank.cardissuing.card.domain.Card;
import bank.cardissuing.card.domain.CardStatus;
import bank.cardissuing.card.infrastructure.CardRepository;
import bank.cardissuing.common.exception.ResourceNotFoundException;
import bank.cardissuing.fraud.application.FraudService;
import bank.cardissuing.fraud.application.FraudService.Assessment;
import bank.cardissuing.fraud.application.FraudService.Decision;
import bank.cardissuing.funds.application.FundsRouter;
import bank.cardissuing.funds.domain.AuthorizationHold;
import bank.cardissuing.funds.domain.FundsPort;
import bank.cardissuing.funds.infrastructure.AuthorizationHoldRepository;
import bank.cardissuing.idempotency.application.IdempotencyService;
import bank.cardissuing.transaction.domain.AuthorizationRequest;
import bank.cardissuing.transaction.domain.AuthorizationResponse;
import bank.cardissuing.transaction.domain.ResponseCode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * The authorizer. Two questions per operation — what card is this (CMS) and are there
 * funds (the port the router picks) — then a decision that reserves but never debits.
 *
 * <p>Order of checks, cheapest and most local first: card state → card controls
 * (channel, international, per-transaction cap) → online fraud → velocity limits →
 * funds. A decline at any step answers without touching the steps after it, so a
 * switched-off channel or a suspected fraud never reaches the core.
 *
 * <p>Declines are answers with an ISO code, not HTTP errors: a switch expects a 0110
 * for every 0100. Only "card does not exist" and infrastructure failures throw.
 * Every decision, approved or not, is remembered for the fraud rules and the console.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthorizationServiceImpl implements AuthorizationService {

    /** Days an approved-but-uncaptured authorization keeps funds reserved. */
    @Value("${holds.default-days:7}")
    private int holdDays = 7;

    private final CardRepository cardRepository;
    private final FundsRouter router;
    private final ControlsPolicy controls;
    private final FraudService fraud;
    private final LimitsPolicy limits;
    private final AuthorizationHoldRepository holds;
    private final IdempotencyService idempotency;
    private final ObjectMapper json;

    private record Outcome(AuthorizationResponse response, Assessment risk) { }

    @Override
    @Transactional
    public AuthorizationResponse authorize(AuthorizationRequest request, String idempotencyKey) {
        Optional<AuthorizationResponse> replay = replay(idempotencyKey);
        if (replay.isPresent()) return replay.get();

        Card card = cardRepository.findById(request.getCardId())
                .orElseThrow(() -> new ResourceNotFoundException("Card", "id", request.getCardId()));
        String cardType = card.getProduct() != null ? card.getProduct().getCardType().name() : null;

        Outcome out = decide(card, request, idempotencyKey, cardType);
        fraud.record(card, request, out.response(), out.risk());
        remember(idempotencyKey, out.response());
        log.info("Authorization {} card={} amount={} channel={} code={} risk={} merchant={}",
                out.response().isApproved() ? "APPROVED" : "DECLINED", card.getId(), request.getAmount(),
                request.channelOrDefault(), out.response().getResponseCode(), out.response().getRiskScore(), request.getMerchantName());
        return out.response();
    }

    private Outcome decide(Card card, AuthorizationRequest request, String idempotencyKey, String cardType) {
        if (card.getStatus() != CardStatus.ACTIVE) {
            return new Outcome(AuthorizationResponse.decline(ResponseCode.RESTRICTED_CARD, "card is " + card.getStatus(), cardType), null);
        }
        if (card.getExpiryDate() != null && card.getExpiryDate().isBefore(LocalDate.now())) {
            return new Outcome(AuthorizationResponse.decline(ResponseCode.EXPIRED_CARD, "expired " + card.getExpiryDate(), cardType), null);
        }

        Optional<Breach> control = controls.check(card, request);
        if (control.isPresent()) {
            return new Outcome(AuthorizationResponse.decline(control.get().code(), control.get().detail(), cardType), null);
        }

        Assessment risk = fraud.assess(card, request);
        if (risk.decision() == Decision.DECLINE) {
            return new Outcome(AuthorizationResponse.decline(ResponseCode.SUSPECTED_FRAUD, risk.reasonsCsv(), cardType)
                    .withRisk(risk.score(), risk.reasons()), risk);
        }
        if (risk.decision() == Decision.STEP_UP) {
            AuthorizationResponse r = AuthorizationResponse.decline(ResponseCode.AUTHENTICATION_REQUIRED,
                    "step-up required (" + risk.reasonsCsv() + ")", cardType).withRisk(risk.score(), risk.reasons());
            r.setChallengeId(risk.challengeToken());
            r.setOtpHint(risk.otpHint());
            return new Outcome(r, risk);
        }

        FundsPort port = router.forCard(card);

        Optional<Breach> limit = limits.check(card, request.getAmount());
        if (limit.isPresent()) {
            return new Outcome(AuthorizationResponse.decline(limit.get().code(), limit.get().detail(), cardType)
                    .withRisk(risk.score(), risk.reasons()), risk);
        }

        BigDecimal available = port.available(card);
        if (available.compareTo(request.getAmount()) < 0) {
            return new Outcome(AuthorizationResponse.decline(ResponseCode.INSUFFICIENT_FUNDS,
                    "requested " + request.getAmount() + ", available " + available, cardType)
                    .withRisk(risk.score(), risk.reasons()), risk);
        }

        AuthorizationHold hold = new AuthorizationHold(card, newApprovalCode(), request.getAmount(),
                request.getMerchantName(), request.getMerchantId(), request.transactionTypeOrDefault(),
                LocalDateTime.now().plusDays(holdDays));
        hold.setIdempotencyKey(idempotencyKey);
        port.hold(card, hold);
        holds.save(hold);

        return new Outcome(AuthorizationResponse.approve(hold.getApprovalCode(), available.subtract(request.getAmount()), cardType)
                .withRisk(risk.score(), risk.reasons()), risk);
    }

    @Override
    @Transactional
    public AuthorizationHold capture(String approvalCode, BigDecimal amount) {
        AuthorizationHold hold = get(approvalCode);
        Card card = hold.getCard();
        hold.capture(amount != null ? amount : hold.getAmount());
        router.forCard(card).capture(card, hold);
        log.info("Captured {} of {} on {}", hold.getCapturedAmount(), hold.getAmount(), approvalCode);
        return holds.save(hold);
    }

    @Override
    @Transactional
    public AuthorizationHold reverse(String approvalCode) {
        AuthorizationHold hold = get(approvalCode);
        Card card = hold.getCard();
        hold.release();
        router.forCard(card).release(card, hold);
        log.info("Reversed {} on {}", hold.getAmount(), approvalCode);
        return holds.save(hold);
    }

    @Override
    public AuthorizationHold get(String approvalCode) {
        return holds.findByApprovalCode(approvalCode)
                .orElseThrow(() -> new ResourceNotFoundException("AuthorizationHold", "approvalCode", approvalCode));
    }

    private Optional<AuthorizationResponse> replay(String key) {
        if (key == null || key.isBlank()) return Optional.empty();
        return idempotency.getExistingResponse(key).map(cached -> {
            try {
                log.info("Idempotent replay for key={}", key);
                return json.readValue(cached, AuthorizationResponse.class);
            } catch (JsonProcessingException e) {
                log.error("Cached authorization for key={} is unreadable; re-authorizing", key, e);
                return null;
            }
        });
    }

    private void remember(String key, AuthorizationResponse response) {
        if (key == null || key.isBlank()) return;
        try {
            idempotency.saveResponse(key, json.writeValueAsString(response));
        } catch (JsonProcessingException e) {
            log.error("Could not cache authorization for key={}", key, e);
        }
    }

    private static String newApprovalCode() {
        return "AUTH-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
    }
}
