package bank.cardissuing.standin;

import bank.cardissuing.audit.application.AuditService;
import bank.cardissuing.card.domain.Card;
import bank.cardissuing.card.domain.Channel;
import bank.cardissuing.common.exception.BusinessException;
import bank.cardissuing.funds.application.FundsRouter;
import bank.cardissuing.funds.domain.AuthorizationHold;
import bank.cardissuing.funds.domain.FundsPort;
import bank.cardissuing.funds.domain.HoldStatus;
import bank.cardissuing.funds.infrastructure.AuthorizationHoldRepository;
import bank.cardissuing.funds.reconciliation.ReconciliationItem;
import bank.cardissuing.funds.reconciliation.ReconciliationItemRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StandInServiceTest {

    @Mock AuthorizationHoldRepository holds;
    @Mock ReconciliationItemRepository items;
    @Mock FundsRouter router;
    @Mock FundsPort port;
    @Mock AuditService audit;
    StandInSettings settings = new StandInSettings();
    StandInService service;
    Card card = new Card();

    @BeforeEach
    void setUp() {
        card.setId(7L);
        card.setExternalAccountId("1432");
        service = new StandInService(settings, holds, items, router, audit);
        when(router.forCard(card)).thenReturn(port);
        when(holds.standInAmountSince(eq(card), any())).thenReturn(BigDecimal.ZERO);
        when(holds.countByCardAndStandInTrueAndCreatedAtAfter(eq(card), any())).thenReturn(0L);
        when(holds.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(items.findByItemKey(anyString())).thenReturn(Optional.empty());
        when(items.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void refuse_appliesEveryLimit() {
        assertTrue(service.refuse(card, new BigDecimal("100"), Channel.POS).isEmpty());
        assertTrue(service.refuse(card, new BigDecimal("2000.01"), Channel.POS).get().reason().contains("over stand-in max"));
        assertTrue(service.refuse(card, new BigDecimal("100"), Channel.ATM).get().reason().contains("ATM"));
        when(holds.standInAmountSince(eq(card), any())).thenReturn(new BigDecimal("4950"));
        assertTrue(service.refuse(card, new BigDecimal("100"), Channel.POS).get().reason().contains("daily total"));
        when(holds.standInAmountSince(eq(card), any())).thenReturn(BigDecimal.ZERO);
        when(holds.countByCardAndStandInTrueAndCreatedAtAfter(eq(card), any())).thenReturn(5L);
        assertTrue(service.refuse(card, new BigDecimal("100"), Channel.POS).get().reason().contains("count"));
        settings.setEnabled(false);
        assertTrue(service.refuse(card, new BigDecimal("1"), Channel.POS).get().reason().contains("disabled"));
    }

    private AuthorizationHold pending(String code) {
        AuthorizationHold h = new AuthorizationHold(card, code, new BigDecimal("50"), "M", "M-1", "PURCHASE", LocalDateTime.now().plusDays(7));
        service.markStandIn(h);
        return h;
    }

    @Test
    void settle_placesTheReservationWhenTheCoreIsBack() {
        AuthorizationHold h = pending("AUTH-1");
        assertTrue(h.isStandIn() && h.isStandInPending() && h.getExternalRef() == null);
        when(holds.findByStandInTrueAndStandInPendingTrueOrderByCreatedAtAsc()).thenReturn(List.of(h));
        doAnswer(inv -> { ((AuthorizationHold) inv.getArgument(1)).setExternalRef("core-77"); return null; }).when(port).hold(eq(card), any());
        assertEquals(1, service.settlePending());
        assertFalse(h.isStandInPending());
        assertEquals("core-77", h.getExternalRef());
        assertNotNull(h.getStandInSettledAt());
        verify(audit).log("STAND_IN_SETTLED", "AuthorizationHold", "AUTH-1", "SYSTEM");
    }

    @Test
    void settle_keepsWaitingWhileTheCoreIsStillDown() {
        AuthorizationHold h = pending("AUTH-2");
        when(holds.findByStandInTrueAndStandInPendingTrueOrderByCreatedAtAsc()).thenReturn(List.of(h));
        doThrow(new BusinessException("CORE_UNAVAILABLE", "down", HttpStatus.SERVICE_UNAVAILABLE)).when(port).hold(eq(card), any());
        assertEquals(0, service.settlePending());
        assertTrue(h.isStandInPending());
        verifyNoInteractions(items);
    }

    @Test
    void settle_flagsAReservationTheCoreRefuses() {
        AuthorizationHold h = pending("AUTH-3");
        when(holds.findByStandInTrueAndStandInPendingTrueOrderByCreatedAtAsc()).thenReturn(List.of(h));
        doThrow(new BusinessException("CORE_INSUFFICIENT_FUNDS", "only 10 left", HttpStatus.UNPROCESSABLE_ENTITY)).when(port).hold(eq(card), any());
        assertEquals(1, service.settlePending());
        assertFalse(h.isStandInPending());
        ArgumentCaptor<ReconciliationItem> cap = ArgumentCaptor.forClass(ReconciliationItem.class);
        verify(items).save(cap.capture());
        assertEquals(ReconciliationItem.Type.STAND_IN_REJECTED, cap.getValue().getType());
        assertEquals("AUTH-3", cap.getValue().getApprovalCode());
        assertEquals(new BigDecimal("50"), cap.getValue().getCmsAmount());
        verify(audit).log("STAND_IN_REJECTED", "AuthorizationHold", "AUTH-3", "SYSTEM");
    }

    @Test
    void settle_skipsHoldsThatAreNoLongerHeld() {
        AuthorizationHold h = pending("AUTH-4");
        h.release();
        assertEquals(HoldStatus.RELEASED, h.getStatus());
        when(holds.findByStandInTrueAndStandInPendingTrueOrderByCreatedAtAsc()).thenReturn(List.of(h));
        assertEquals(1, service.settlePending());
        assertFalse(h.isStandInPending());
        verify(port, never()).hold(any(), any());
    }
}
