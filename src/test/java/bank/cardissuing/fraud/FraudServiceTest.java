package bank.cardissuing.fraud;

import bank.cardissuing.card.domain.*;
import bank.cardissuing.card.infrastructure.CardRepository;
import bank.cardissuing.common.exception.BusinessException;
import bank.cardissuing.customer.domain.Customer;
import bank.cardissuing.fraud.application.FraudService;
import bank.cardissuing.fraud.application.FraudService.Assessment;
import bank.cardissuing.fraud.application.FraudService.Decision;
import bank.cardissuing.fraud.application.FraudSettings;
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
import bank.cardissuing.transaction.domain.ResponseCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FraudServiceTest {

    @Mock AuthorizationAttemptRepository attempts;
    @Mock FraudAlertRepository alerts;
    @Mock BlockedEntityRepository blocklist;
    @Mock StepUpChallengeRepository challenges;
    @Mock CardRepository cards;

    FraudSettings settings = new FraudSettings();
    FraudService service;
    Card card;

    @BeforeEach
    void setUp() {
        service = new FraudService(settings, attempts, alerts, blocklist, challenges, cards);
        card = new Card(new Customer(), "1111", CardStatus.ACTIVE, LocalDate.now().plusYears(1));
        card.setId(9L);
        card.setCreatedAt(LocalDateTime.now().minusDays(30));
        card.setProduct(new CardProduct("P", "P", CardType.PREPAID, PaymentType.PREPAID, CardNetwork.VISA, "4", "MXN", null, true));
        when(blocklist.findByTypeAndValueAndActiveTrue(any(), any())).thenReturn(Optional.empty());
        when(attempts.countByCardAndCreatedAtAfter(eq(card), any())).thenReturn(0L);
        when(attempts.countByCardAndApprovedFalseAndCreatedAtAfter(eq(card), any())).thenReturn(0L);
        when(attempts.findByCardAndApprovedTrueAndCreatedAtAfter(eq(card), any())).thenReturn(List.of());
        when(attempts.findFirstByCardAndApprovedTrueOrderByCreatedAtDesc(card)).thenReturn(Optional.empty());
        when(attempts.countByMerchantIdAndApprovedFalseAndCreatedAtAfter(any(), any())).thenReturn(0L);
        when(alerts.save(any())).thenAnswer(i -> i.getArgument(0));
        when(challenges.save(any())).thenAnswer(i -> i.getArgument(0));
        when(attempts.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    private AuthorizationRequest req(String amount, String channel, String merchantId, String country) {
        return AuthorizationRequest.builder().cardId(9L).amount(new BigDecimal(amount)).merchantName("Tienda")
                .merchantId(merchantId).channel(channel).countryCode(country).build();
    }

    private AuthorizationAttempt approved(String amount, String country, LocalDateTime at) {
        AuthorizationAttempt a = new AuthorizationAttempt(card, new BigDecimal(amount), "T", "M", "POS", country, "00", true, "AUTH-X", 0, "", false);
        a.setCreatedAt(at);
        return a;
    }

    @Test
    void cleanHistory_approves_withZeroScore() {
        Assessment a = service.assess(card, req("100", "POS", "M-1", null));
        assertEquals(Decision.APPROVE, a.decision());
        assertEquals(0, a.score());
        assertTrue(a.reasons().isEmpty());
        verify(alerts, never()).save(any());
    }

    @Test
    void blocklistedMerchant_declines_withoutArithmetic_andAlerts() {
        when(blocklist.findByTypeAndValueAndActiveTrue(BlockedEntity.Type.MERCHANT_ID, "M-BAD"))
                .thenReturn(Optional.of(new BlockedEntity(BlockedEntity.Type.MERCHANT_ID, "M-BAD", BlockedEntity.Source.INTERNAL, "x", "y")));
        Assessment a = service.assess(card, req("1", "POS", "M-BAD", null));
        assertEquals(Decision.DECLINE, a.decision());
        assertEquals(100, a.score());
        assertEquals(List.of("BLOCKLIST_MERCHANT"), a.reasons());
        ArgumentCaptor<FraudAlert> alert = ArgumentCaptor.forClass(FraudAlert.class);
        verify(alerts).save(alert.capture());
        assertEquals(FraudAlert.Type.DECLINED, alert.getValue().getType());
    }

    @Test
    void repeatedDeclines_onEcommerce_askForStepUp_withAChallenge() {
        when(attempts.countByCardAndApprovedFalseAndCreatedAtAfter(eq(card), any())).thenReturn(3L);
        Assessment a = service.assess(card, req("40", "ECOMMERCE", "M-1", null));
        assertEquals(Decision.STEP_UP, a.decision());
        assertEquals(50, a.score());
        assertEquals(List.of("DECLINES_30M"), a.reasons());
        assertNotNull(a.challengeToken());
        assertNull(a.otpHint(), "otp is not exposed by default");
        ArgumentCaptor<StepUpChallenge> c = ArgumentCaptor.forClass(StepUpChallenge.class);
        verify(challenges).save(c.capture());
        assertEquals(6, c.getValue().getOtp().length());
        assertEquals(new BigDecimal("40"), c.getValue().getAmount());
    }

    @Test
    void repeatedDeclines_onPos_cannotStepUp_soItDeclines() {
        when(attempts.countByCardAndApprovedFalseAndCreatedAtAfter(eq(card), any())).thenReturn(3L);
        Assessment a = service.assess(card, req("40", "POS", "M-1", null));
        assertEquals(Decision.DECLINE, a.decision());
        assertTrue(a.reasons().contains("STEP_UP_UNAVAILABLE_POS"));
        verify(challenges, never()).save(any());
    }

    @Test
    void velocityPlusDeclines_isAnOutrightDecline() {
        when(attempts.countByCardAndCreatedAtAfter(eq(card), any())).thenReturn(6L);
        when(attempts.countByCardAndApprovedFalseAndCreatedAtAfter(eq(card), any())).thenReturn(3L);
        Assessment a = service.assess(card, req("40", "ECOMMERCE", "M-1", null));
        assertEquals(Decision.DECLINE, a.decision());
        assertEquals(90, a.score());
        assertEquals(List.of("VELOCITY_10M", "DECLINES_30M"), a.reasons());
    }

    @Test
    void enumeration_atAMerchant_scoresAndRaisesOneAlert() {
        when(attempts.countByMerchantIdAndApprovedFalseAndCreatedAtAfter(eq("M-ENUM"), any())).thenReturn(4L);
        when(attempts.countDistinctCardsDeclinedAtMerchant(eq("M-ENUM"), any())).thenReturn(3L);
        when(alerts.existsByMerchantIdAndTypeAndStatusAndCreatedAtAfter(eq("M-ENUM"), eq(FraudAlert.Type.ENUMERATION), eq(FraudAlert.Status.OPEN), any()))
                .thenReturn(false, true);

        Assessment a = service.assess(card, req("10", "ECOMMERCE", "M-ENUM", null));
        assertEquals(Decision.STEP_UP, a.decision());
        assertEquals(60, a.score());
        assertTrue(a.reasons().contains("ENUMERATION"));

        service.assess(card, req("10", "ECOMMERCE", "M-ENUM", null));
        // one ENUMERATION alert for the merchant, plus one STEP_UP alert per assessment
        assertEquals(1, mockingDetails(alerts).getInvocations().stream()
                .filter(i -> i.getMethod().getName().equals("save"))
                .filter(i -> ((FraudAlert) i.getArgument(0)).getType() == FraudAlert.Type.ENUMERATION).count());
    }

    @Test
    void amountAnomaly_againstTheCardsOwnHabit() {
        LocalDateTime t = LocalDateTime.now().minusDays(2);
        when(attempts.findByCardAndApprovedTrueAndCreatedAtAfter(eq(card), any()))
                .thenReturn(List.of(approved("10", null, t), approved("12", null, t), approved("8", null, t)));
        Assessment a = service.assess(card, req("100", "POS", "M-1", null));
        assertEquals(List.of("AMOUNT_ANOMALY"), a.reasons());
        assertEquals(30, a.score());
        assertEquals(Decision.APPROVE, a.decision(), "30 is below review");
    }

    @Test
    void firstEverTransaction_aboveTheCap_isNoted() {
        Assessment a = service.assess(card, req("3500", "POS", "M-1", null));
        assertEquals(List.of("FIRST_TX_HIGH"), a.reasons());
    }

    @Test
    void geoJump_and_newCardHighAmount() {
        when(attempts.findFirstByCardAndApprovedTrueOrderByCreatedAtDesc(card))
                .thenReturn(Optional.of(approved("20", "MX", LocalDateTime.now().minusMinutes(30))));
        card.setCreatedAt(LocalDateTime.now().minusHours(2));
        Assessment a = service.assess(card, req("1500", "ECOMMERCE", "M-1", "US"));
        assertTrue(a.reasons().contains("GEO_JUMP"));
        assertTrue(a.reasons().contains("NEW_CARD_HIGH"));
        assertEquals(Decision.STEP_UP, a.decision(), "30 + 20 = 50 reaches review");
    }

    @Test
    void stepUp_verifyThenConsumeOnce() {
        StepUpChallenge c = new StepUpChallenge("tok", card, new BigDecimal("40"), "M-1", "ECOMMERCE", "123456", LocalDateTime.now().plusMinutes(5));
        when(challenges.findByToken("tok")).thenReturn(Optional.of(c));

        assertEquals("CHALLENGE_WRONG_CODE", assertThrows(BusinessException.class, () -> service.verify("tok", "000000")).getErrorCode());
        assertEquals(1, c.getAttempts());
        assertEquals(StepUpChallenge.Status.VERIFIED, service.verify("tok", "123456").getStatus());

        AuthorizationRequest retry = req("40", "ECOMMERCE", "M-1", null);
        retry.setStepUpToken("tok");
        Assessment a = service.assess(card, retry);
        assertEquals(Decision.APPROVE, a.decision());
        assertEquals(List.of("STEP_UP_VERIFIED"), a.reasons());
        assertEquals(StepUpChallenge.Status.CONSUMED, c.getStatus());

        Assessment again = service.assess(card, retry);
        assertEquals(Decision.DECLINE, again.decision(), "a token is good exactly once");
        assertEquals(List.of("STEP_UP_INVALID"), again.reasons());
    }

    @Test
    void stepUp_wrongAmountOrCard_isRejected() {
        StepUpChallenge c = new StepUpChallenge("tok", card, new BigDecimal("40"), "M-1", "ECOMMERCE", "123456", LocalDateTime.now().plusMinutes(5));
        c.setStatus(StepUpChallenge.Status.VERIFIED);
        when(challenges.findByToken("tok")).thenReturn(Optional.of(c));
        AuthorizationRequest retry = req("41", "ECOMMERCE", "M-1", null);
        retry.setStepUpToken("tok");
        assertEquals(Decision.DECLINE, service.assess(card, retry).decision());
    }

    @Test
    void stepUp_tooManyWrongCodes_failsForGood() {
        StepUpChallenge c = new StepUpChallenge("tok", card, new BigDecimal("40"), "M-1", "ECOMMERCE", "123456", LocalDateTime.now().plusMinutes(5));
        when(challenges.findByToken("tok")).thenReturn(Optional.of(c));
        for (int i = 0; i < 3; i++) assertThrows(BusinessException.class, () -> service.verify("tok", "000000"));
        assertEquals(StepUpChallenge.Status.FAILED, c.getStatus());
        assertEquals("CHALLENGE_NOT_PENDING", assertThrows(BusinessException.class, () -> service.verify("tok", "123456")).getErrorCode());
    }

    @Test
    void record_keepsEveryDecisionWithItsReasons() {
        AuthorizationResponse resp = AuthorizationResponse.decline(ResponseCode.SUSPECTED_FRAUD, "x", "PREPAID");
        Assessment a = new Assessment(90, List.of("VELOCITY_10M", "DECLINES_30M"), Decision.DECLINE, null, null);
        AuthorizationAttempt saved = service.record(card, req("40", "ECOMMERCE", "M-1", "MX"), resp, a);
        assertEquals("59", saved.getResponseCode());
        assertFalse(saved.isApproved());
        assertEquals(90, saved.getRiskScore());
        assertEquals("VELOCITY_10M,DECLINES_30M", saved.getRiskReasons());
        assertEquals("ECOMMERCE", saved.getChannel());
    }

    @Test
    void review_blockMerchant_addsToBlocklist_andCloses() {
        FraudAlert alert = new FraudAlert(card, "M-BAD", "Tienda", FraudAlert.Type.DECLINED, 90, "x", BigDecimal.TEN);
        alert.setId(5L);
        when(alerts.findById(5L)).thenReturn(Optional.of(alert));
        when(blocklist.findByTypeAndValue(BlockedEntity.Type.MERCHANT_ID, "M-BAD")).thenReturn(Optional.empty());
        when(blocklist.save(any())).thenAnswer(i -> i.getArgument(0));

        FraudAlert out = service.review(5L, "BLOCK_MERCHANT", "card testing", "ana");
        assertEquals(FraudAlert.Status.REVIEWED, out.getStatus());
        assertEquals("BLOCK_MERCHANT", out.getActionTaken());
        ArgumentCaptor<BlockedEntity> b = ArgumentCaptor.forClass(BlockedEntity.class);
        verify(blocklist).save(b.capture());
        assertEquals("M-BAD", b.getValue().getValue());
        assertTrue(b.getValue().isActive());
    }
}
