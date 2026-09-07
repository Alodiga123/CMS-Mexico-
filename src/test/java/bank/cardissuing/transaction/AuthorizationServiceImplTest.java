package bank.cardissuing.transaction;

import bank.cardissuing.card.domain.*;
import bank.cardissuing.card.infrastructure.CardRepository;
import bank.cardissuing.common.exception.BusinessException;
import bank.cardissuing.common.exception.ResourceNotFoundException;
import bank.cardissuing.customer.domain.Customer;
import bank.cardissuing.funds.application.FundsRouter;
import bank.cardissuing.funds.domain.AuthorizationHold;
import bank.cardissuing.funds.domain.FundsPort;
import bank.cardissuing.funds.domain.HoldStatus;
import bank.cardissuing.funds.infrastructure.AuthorizationHoldRepository;
import bank.cardissuing.idempotency.application.IdempotencyService;
import bank.cardissuing.transaction.application.AuthorizationServiceImpl;
import bank.cardissuing.transaction.application.LimitsPolicy;
import bank.cardissuing.transaction.domain.AuthorizationRequest;
import bank.cardissuing.transaction.domain.AuthorizationResponse;
import bank.cardissuing.transaction.domain.ResponseCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthorizationServiceImplTest {

    @Mock CardRepository cardRepository;
    @Mock FundsRouter router;
    @Mock LimitsPolicy limits;
    @Mock AuthorizationHoldRepository holds;
    @Mock IdempotencyService idempotency;
    @Mock FundsPort port;

    AuthorizationServiceImpl service;
    Card card;

    @BeforeEach
    void setUp() {
        service = new AuthorizationServiceImpl(cardRepository, router, limits, holds, idempotency, new ObjectMapper());
        CardProduct product = new CardProduct("PRE-01", "Prepago", CardType.PREPAID, PaymentType.PREPAID,
                CardNetwork.VISA, "453211", "MXN", null, true);
        card = new Card(new Customer(), "4321", CardStatus.ACTIVE, LocalDate.now().plusYears(2));
        card.setId(7L);
        card.setProduct(product);
    }

    private AuthorizationRequest req(String amount) {
        return AuthorizationRequest.builder().cardId(7L).amount(new BigDecimal(amount)).merchantName("Cafeteria").build();
    }

    private void cardFound() {
        when(cardRepository.findById(7L)).thenReturn(Optional.of(card));
    }

    private void routed() {
        when(router.forCard(card)).thenReturn(port);
        when(limits.check(eq(card), any())).thenReturn(Optional.empty());
    }

    @Test
    void authorize_whenCardNotFound_shouldThrow() {
        when(cardRepository.findById(7L)).thenReturn(Optional.empty());
        assertThrows(ResourceNotFoundException.class, () -> service.authorize(req("100"), null));
        verify(holds, never()).save(any());
    }

    @Test
    void authorize_whenCardNotActive_shouldDeclineRestricted() {
        card.setStatus(CardStatus.BLOCKED);
        cardFound();
        AuthorizationResponse r = service.authorize(req("100"), null);
        assertFalse(r.isApproved());
        assertEquals(ResponseCode.RESTRICTED_CARD.getCode(), r.getResponseCode());
        verifyNoInteractions(router);
    }

    @Test
    void authorize_whenCardExpired_shouldDecline54() {
        card.setExpiryDate(LocalDate.now().minusDays(1));
        cardFound();
        AuthorizationResponse r = service.authorize(req("100"), null);
        assertEquals("54", r.getResponseCode());
    }

    @Test
    void authorize_whenLimitExceeded_shouldDecline61_withoutTouchingFunds() {
        cardFound();
        when(router.forCard(card)).thenReturn(port);
        when(limits.check(eq(card), any()))
                .thenReturn(Optional.of(new LimitsPolicy.Breach(ResponseCode.EXCEEDS_LIMIT, "daily")));
        AuthorizationResponse r = service.authorize(req("100"), null);
        assertEquals("61", r.getResponseCode());
        verify(port, never()).available(any());
        verify(holds, never()).save(any());
    }

    @Test
    void authorize_whenInsufficientFunds_shouldDecline51() {
        cardFound(); routed();
        when(port.available(card)).thenReturn(new BigDecimal("50"));
        AuthorizationResponse r = service.authorize(req("100"), null);
        assertFalse(r.isApproved());
        assertEquals("51", r.getResponseCode());
        verify(port, never()).hold(any(), any());
    }

    @Test
    void authorize_whenSuccess_shouldHoldAndReturnAvailableAfter() {
        cardFound(); routed();
        when(port.available(card)).thenReturn(new BigDecimal("500"));
        when(holds.save(any())).thenAnswer(i -> i.getArgument(0));

        AuthorizationResponse r = service.authorize(req("120"), null);

        assertTrue(r.isApproved());
        assertEquals("00", r.getResponseCode());
        assertTrue(r.getApprovalCode().startsWith("AUTH-"));
        assertEquals(new BigDecimal("380"), r.getAmount());
        assertEquals("PREPAID", r.getCardType());

        ArgumentCaptor<AuthorizationHold> saved = ArgumentCaptor.forClass(AuthorizationHold.class);
        verify(holds).save(saved.capture());
        assertEquals(HoldStatus.HELD, saved.getValue().getStatus());
        assertEquals(new BigDecimal("120"), saved.getValue().getAmount());
        verify(port).hold(eq(card), any());
    }

    @Test
    void authorize_withIdempotencyKey_shouldReplayWithoutSecondHold() throws Exception {
        AuthorizationResponse cached = AuthorizationResponse.approve("AUTH-CACHED", new BigDecimal("1"), "PREPAID");
        when(idempotency.getExistingResponse("k1")).thenReturn(Optional.of(new ObjectMapper().writeValueAsString(cached)));

        AuthorizationResponse r = service.authorize(req("120"), "k1");

        assertEquals("AUTH-CACHED", r.getApprovalCode());
        verifyNoInteractions(cardRepository, router, holds);
    }

    @Test
    void authorize_withIdempotencyKey_shouldRememberDecision() {
        when(idempotency.getExistingResponse("k2")).thenReturn(Optional.empty());
        cardFound(); routed();
        when(port.available(card)).thenReturn(new BigDecimal("500"));
        when(holds.save(any())).thenAnswer(i -> i.getArgument(0));

        service.authorize(req("10"), "k2");

        verify(idempotency).saveResponse(eq("k2"), anyString());
    }

    @Test
    void capture_whenHeld_shouldMoveMoneyAndMarkCaptured() {
        AuthorizationHold h = new AuthorizationHold(card, "AUTH-1", new BigDecimal("120"), "Cafeteria", null, "PURCHASE", LocalDateTime.now().plusDays(7));
        when(holds.findByApprovalCode("AUTH-1")).thenReturn(Optional.of(h));
        when(router.forCard(card)).thenReturn(port);
        when(holds.save(any())).thenAnswer(i -> i.getArgument(0));

        AuthorizationHold out = service.capture("AUTH-1", null);

        assertEquals(HoldStatus.CAPTURED, out.getStatus());
        assertEquals(new BigDecimal("120"), out.getCapturedAmount());
        verify(port).capture(card, h);
    }

    @Test
    void capture_partial_shouldKeepCapturedAmount() {
        AuthorizationHold h = new AuthorizationHold(card, "AUTH-2", new BigDecimal("120"), "Cafeteria", null, "PREAUTH", LocalDateTime.now().plusDays(7));
        when(holds.findByApprovalCode("AUTH-2")).thenReturn(Optional.of(h));
        when(router.forCard(card)).thenReturn(port);
        when(holds.save(any())).thenAnswer(i -> i.getArgument(0));

        AuthorizationHold out = service.capture("AUTH-2", new BigDecimal("95.50"));
        assertEquals(new BigDecimal("95.50"), out.getCapturedAmount());
    }

    @Test
    void reverse_whenAlreadyCaptured_shouldConflict() {
        AuthorizationHold h = new AuthorizationHold(card, "AUTH-3", new BigDecimal("10"), "Cafeteria", null, "PURCHASE", LocalDateTime.now().plusDays(7));
        h.capture(new BigDecimal("10"));
        when(holds.findByApprovalCode("AUTH-3")).thenReturn(Optional.of(h));

        BusinessException ex = assertThrows(BusinessException.class, () -> service.reverse("AUTH-3"));
        assertEquals("HOLD_INVALID_STATE", ex.getErrorCode());
        verifyNoInteractions(port);
    }

    @Test
    void reverse_whenHeld_shouldReleaseFunds() {
        AuthorizationHold h = new AuthorizationHold(card, "AUTH-4", new BigDecimal("10"), "Cafeteria", null, "PURCHASE", LocalDateTime.now().plusDays(7));
        when(holds.findByApprovalCode("AUTH-4")).thenReturn(Optional.of(h));
        when(router.forCard(card)).thenReturn(port);
        when(holds.save(any())).thenAnswer(i -> i.getArgument(0));

        AuthorizationHold out = service.reverse("AUTH-4");
        assertEquals(HoldStatus.RELEASED, out.getStatus());
        verify(port).release(card, h);
    }
}
