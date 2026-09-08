package bank.cardissuing.card;

import bank.cardissuing.audit.application.AuditService;
import bank.cardissuing.card.application.CardProductService;
import bank.cardissuing.card.application.CardProductService.ProductInput;
import bank.cardissuing.card.domain.CardNetwork;
import bank.cardissuing.card.domain.CardProduct;
import bank.cardissuing.card.domain.CardType;
import bank.cardissuing.card.domain.PaymentType;
import bank.cardissuing.card.infrastructure.CardProductRepository;
import bank.cardissuing.common.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CardProductServiceTest {

    @Mock CardProductRepository repo;
    @Mock AuditService audit;
    CardProductService service;
    List<CardProduct> stored = new ArrayList<>();

    @BeforeEach
    void setUp() {
        service = new CardProductService(repo, audit);
        when(repo.findAll()).thenReturn(stored);
        when(repo.findByProductCode(anyString())).thenAnswer(inv -> stored.stream().filter(p -> p.getProductCode().equals(inv.getArgument(0))).findFirst());
        when(repo.findById(any())).thenAnswer(inv -> stored.stream().filter(p -> p.getId().equals(inv.getArgument(0))).findFirst());
        when(repo.save(any())).thenAnswer(inv -> {
            CardProduct p = inv.getArgument(0);
            if (p.getId() == null) { p.setId((long) (stored.size() + 1)); stored.add(p); }
            return p;
        });
    }

    private static ProductInput input(String code, String name, String type, String bin) {
        return new ProductInput(code, name, type, "PREPAID", "VISA", bin, "MXN", null, null, null, null, "MX", null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    @Test
    void create_honoursTheGivenCode_normalised() {
        CardProduct p = service.create(input(" pre-mx ", "Prepago MX", "prepaid", "453212"), "ops");
        assertEquals("PRE-MX", p.getProductCode());
        assertEquals(CardType.PREPAID, p.getCardType());
        assertEquals(PaymentType.PREPAID, p.getPaymentType());
        assertEquals(CardNetwork.VISA, p.getNetwork());
        assertEquals("MX", p.getCountry());
        assertEquals(new BigDecimal("1000"), p.getDailyLimit());
        assertTrue(p.isActive());
        verify(audit).log("CREATE_PRODUCT", "CardProduct", "PRE-MX", "ops");
    }

    @Test
    void create_generatesAReadableUniqueCode_whenNoneGiven() {
        assertEquals("DEB-MX-001", service.create(input(null, "Debito uno", "DEBIT", "453212"), null).getProductCode());
        assertEquals("DEB-MX-002", service.create(input("", "Debito dos", "DEBIT", "453212"), null).getProductCode());
        assertEquals("CRE-MX-001", service.create(input(null, "Credito", "CREDIT", "453212"), null).getProductCode());
    }

    @Test
    void create_refusesADuplicateCode_with409() {
        service.create(input("PRE-MX", "Uno", "PREPAID", "453212"), null);
        BusinessException e = assertThrows(BusinessException.class, () -> service.create(input("pre-mx", "Dos", "PREPAID", "453212"), null));
        assertEquals("PRODUCT_CODE_IN_USE", e.getErrorCode());
        assertEquals(409, e.getHttpStatus().value());
    }

    @Test
    void create_rejectsBadInput_with400() {
        assertEquals("PRODUCT_CODE_INVALID", assertThrows(BusinessException.class, () -> service.create(input("x", "N", "PREPAID", "453212"), null)).getErrorCode());
        assertEquals("PRODUCT_CODE_INVALID", assertThrows(BusinessException.class, () -> service.create(input("PRE/MX", "N", "PREPAID", "453212"), null)).getErrorCode());
        assertEquals("PRODUCT_INVALID", assertThrows(BusinessException.class, () -> service.create(input("PRE-MX", " ", "PREPAID", "453212"), null)).getErrorCode());
        assertEquals("PRODUCT_INVALID", assertThrows(BusinessException.class, () -> service.create(input("PRE-MX", "N", "GOLD", "453212"), null)).getErrorCode());
        assertEquals("PRODUCT_INVALID", assertThrows(BusinessException.class, () -> service.create(input("PRE-MX", "N", "PREPAID", "45"), null)).getErrorCode());
        BusinessException neg = assertThrows(BusinessException.class, () -> service.create(new ProductInput("PRE-MX", "N", "PREPAID", "PREPAID", "VISA", "453212", null, null,
                new BigDecimal("-1"), null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null), null));
        assertEquals("PRODUCT_INVALID", neg.getErrorCode());
        assertTrue(stored.isEmpty());
    }

    @Test
    void update_canRenameTheCode_butNotOntoAnotherProduct() {
        CardProduct a = service.create(input("PRE-MX", "A", "PREPAID", "453212"), null);
        CardProduct b = service.create(input("DEB-MX", "B", "DEBIT", "453213"), null);
        CardProduct renamed = service.update(a.getId(), input("PRE-MX-V2", null, null, null), "ops");
        assertEquals("PRE-MX-V2", renamed.getProductCode());
        assertEquals("A", renamed.getProductName());
        assertEquals("PRODUCT_CODE_IN_USE", assertThrows(BusinessException.class, () -> service.update(b.getId(), input("PRE-MX-V2", null, null, null), null)).getErrorCode());
        assertEquals("PRE-MX-V2", service.update(a.getId(), input("pre-mx-v2", "A2", null, null), null).getProductCode());
        assertEquals("PRODUCT_NOT_FOUND", assertThrows(BusinessException.class, () -> service.update(99L, input(null, "X", null, null), null)).getErrorCode());
    }

    @Test
    void update_canDeactivate() {
        CardProduct a = service.create(input("PRE-MX", "A", "PREPAID", "453212"), null);
        ProductInput off = new ProductInput(null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, false);
        assertFalse(service.update(a.getId(), off, null).isActive());
        assertEquals(Optional.of(a), repo.findByProductCode("PRE-MX"));
    }
}
