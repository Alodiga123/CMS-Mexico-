package bank.cardissuing.funds;

import bank.cardissuing.card.domain.*;
import bank.cardissuing.common.exception.BusinessException;
import bank.cardissuing.customer.domain.Customer;
import bank.cardissuing.funds.application.FundsRouter;
import bank.cardissuing.funds.application.HoldExpiryService;
import bank.cardissuing.funds.domain.AuthorizationHold;
import bank.cardissuing.funds.domain.FundsPort;
import bank.cardissuing.funds.domain.HoldStatus;
import bank.cardissuing.funds.infrastructure.AuthorizationHoldRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class HoldExpiryServiceTest {

    @Mock AuthorizationHoldRepository holds;
    @Mock FundsRouter router;
    @Mock FundsPort port;
    @Mock PlatformTransactionManager txManager;

    HoldExpiryService service;
    Card card;
    LocalDateTime now;

    @BeforeEach
    void setUp() {
        service = new HoldExpiryService(holds, router, txManager);
        card = new Card(new Customer(), "1111", CardStatus.ACTIVE, LocalDate.now().plusYears(1));
        card.setId(3L);
        card.setProduct(new CardProduct("P", "P", CardType.PREPAID, PaymentType.PREPAID, CardNetwork.VISA, "400000", "MXN", null, true));
        now = LocalDateTime.now();
    }

    private AuthorizationHold hold(long id, String code) {
        AuthorizationHold h = new AuthorizationHold(card, code, new BigDecimal("50"), "M", null, "PURCHASE", now.minusMinutes(5));
        h.setId(id);
        return h;
    }

    @Test
    void nothingDue_expiresNothing() {
        when(holds.findByStatusAndExpiresAtBefore(HoldStatus.HELD, now)).thenReturn(List.of());
        assertEquals(0, service.expireDue(now));
        verifyNoInteractions(router);
    }

    @Test
    void dueHold_isReleasedAndMarkedExpired() {
        AuthorizationHold h = hold(1L, "AUTH-1");
        when(holds.findByStatusAndExpiresAtBefore(HoldStatus.HELD, now)).thenReturn(List.of(h));
        when(holds.findById(1L)).thenReturn(Optional.of(h));
        when(router.forCard(card)).thenReturn(port);

        assertEquals(1, service.expireDue(now));

        assertEquals(HoldStatus.EXPIRED, h.getStatus());
        verify(port).release(card, h);
        verify(holds).save(h);
        verify(txManager).commit(any());
    }

    @Test
    void oneFailure_doesNotStopTheOthers() {
        AuthorizationHold bad = hold(1L, "AUTH-BAD");
        AuthorizationHold good = hold(2L, "AUTH-GOOD");
        when(holds.findByStatusAndExpiresAtBefore(HoldStatus.HELD, now)).thenReturn(List.of(bad, good));
        when(holds.findById(1L)).thenReturn(Optional.of(bad));
        when(holds.findById(2L)).thenReturn(Optional.of(good));
        when(router.forCard(card)).thenReturn(port);
        doThrow(new BusinessException("CORE_UNAVAILABLE", "down", HttpStatus.SERVICE_UNAVAILABLE))
                .when(port).release(eq(card), eq(bad));

        assertEquals(1, service.expireDue(now));

        assertEquals(HoldStatus.HELD, bad.getStatus(), "failed one stays HELD for the next run");
        assertEquals(HoldStatus.EXPIRED, good.getStatus());
        verify(txManager).rollback(any());
        verify(txManager).commit(any());
    }

    @Test
    void holdCapturedMeanwhile_isSkipped() {
        AuthorizationHold h = hold(1L, "AUTH-1");
        when(holds.findByStatusAndExpiresAtBefore(HoldStatus.HELD, now)).thenReturn(List.of(h));
        h.capture(new BigDecimal("50")); // captured between the query and the per-hold transaction
        when(holds.findById(1L)).thenReturn(Optional.of(h));

        assertEquals(0, service.expireDue(now));
        assertEquals(HoldStatus.CAPTURED, h.getStatus());
        verifyNoInteractions(router);
    }
}
