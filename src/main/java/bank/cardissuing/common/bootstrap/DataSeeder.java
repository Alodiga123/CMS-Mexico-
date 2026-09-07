package bank.cardissuing.common.bootstrap;

import bank.cardissuing.card.domain.*;
import bank.cardissuing.card.infrastructure.CardProductRepository;
import bank.cardissuing.card.infrastructure.CardRepository;
import bank.cardissuing.customer.domain.Customer;
import bank.cardissuing.customer.domain.CustomerType;
import bank.cardissuing.customer.domain.KYC;
import bank.cardissuing.customer.domain.KYCStatus;
import bank.cardissuing.customer.infrastructure.CustomerRepository;
import bank.cardissuing.customer.infrastructure.KYCRepository;
import bank.cardissuing.ledger.domain.EntryType;
import bank.cardissuing.ledger.domain.LedgerAccount;
import bank.cardissuing.ledger.domain.LedgerEntry;
import bank.cardissuing.ledger.infrastructure.LedgerAccountRepository;
import bank.cardissuing.ledger.infrastructure.LedgerEntryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;

@Slf4j
@Component
@Profile("!test")
@RequiredArgsConstructor
public class DataSeeder implements CommandLineRunner {

    private final CardProductRepository cardProductRepository;
    private final CustomerRepository customerRepository;
    private final KYCRepository kycRepository;
    private final CardRepository cardRepository;
    private final LedgerAccountRepository ledgerAccountRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final bank.cardissuing.card.infrastructure.PromotionRepository promotionRepository;

    @Override
    public void run(String... args) throws Exception {
        Promotion cashbackSupermercados = null;
        Promotion bonoVipCredito = null;

        // Seed Products if none exist
        if (cardProductRepository.count() == 0) {
            log.info("Seeding realistic default Card Products...");
            CardProduct debitProd = new CardProduct("PROD-ALODIGA-DEB", "Tarjeta Alodiga Débito Core", CardType.DEBIT, PaymentType.POSTPAID, CardNetwork.VISA, "453211", "USD", BigDecimal.ZERO, new BigDecimal("1500"), new BigDecimal("7000"), new BigDecimal("25000"), "🇺🇸 USA", true);
            CardProduct prepaidProd = new CardProduct("PROD-ALOCASH-PRE", "Tarjeta Alocash Prepago USD", CardType.PREPAID, PaymentType.PREPAID, CardNetwork.ALODIGA_PRIVATE, "601100", "USD", BigDecimal.ZERO, new BigDecimal("500"), new BigDecimal("2500"), new BigDecimal("10000"), "🇻🇪 VZN", true);
            CardProduct creditProd = new CardProduct("PROD-ALOCAS-CRE", "Tarjeta Alocas Crédito Gold", CardType.CREDIT, PaymentType.POSTPAID, CardNetwork.MASTERCARD, "541234", "USD", new BigDecimal("5000"), new BigDecimal("3000"), new BigDecimal("15000"), new BigDecimal("50000"), "🇪🇺 EUR", true);

            cardProductRepository.save(debitProd);
            cardProductRepository.save(prepaidProd);
            cardProductRepository.save(creditProd);

            // Seed Promotions
            cashbackSupermercados = promotionRepository.save(new Promotion("Cashback 5% en Supermercados", "5% de reembolso automático en compras de supermercados e hipermercados", new BigDecimal("5.00"), BigDecimal.ZERO, true, debitProd));
            promotionRepository.save(new Promotion("Descuento 10% en Remesas Alodiga", "10% de descuento en la comisión de envío de dinero con Alocash Pay", BigDecimal.ZERO, new BigDecimal("10.00"), true, prepaidProd));
            bonoVipCredito = promotionRepository.save(new Promotion("Bono Bienvenida VIP Crédito", "0% interés en los primeros 30 días de consumo rotativo", new BigDecimal("2.00"), new BigDecimal("100.00"), true, creditProd));

            log.info("Default Card Products & Promotions seeded successfully!");
        }

        if (cashbackSupermercados == null) {
            cashbackSupermercados = promotionRepository.findAll().stream()
                    .filter(p -> p.getName().startsWith("Cashback 5%"))
                    .findFirst().orElse(null);
        }
        if (bonoVipCredito == null) {
            bonoVipCredito = promotionRepository.findAll().stream()
                    .filter(p -> p.getName().startsWith("Bono Bienvenida"))
                    .findFirst().orElse(null);
        }

        if (customerRepository.count() > 0) {
            log.info("Database customers already seeded.");
            return;
        }

        log.info("Starting initial customer seeding...");

        // 1. Customer 1: Carlos Rodríguez (Persona Natural)
        Customer c1 = new Customer();
        c1.setCustomerType(CustomerType.PERSONA_NATURAL);
        c1.setFullName("Carlos Rodriguez");
        c1.setPhoneNumber("+584141234567");
        c1.setEmail("carlos.rodriguez@example.com");
        c1.setTaxId("RODC800101H21");
        c1 = customerRepository.save(c1);

        KYC kyc1 = new KYC();
        kyc1.setCustomer(c1);
        kyc1.setStatus(KYCStatus.VERIFIED);
        kyc1.setDocumentType("INE_RFC");
        kyc1.setDocumentNumber(c1.getTaxId());
        kycRepository.save(kyc1);

        // 2. Customer 2: Nguyen Van A (Persona Natural)
        Customer c2 = new Customer();
        c2.setCustomerType(CustomerType.PERSONA_NATURAL);
        c2.setFullName("Nguyen Van A");
        c2.setPhoneNumber("+84987654321");
        c2.setEmail("nguyen.vana@example.com");
        c2.setTaxId("NGVA850315M45");
        c2 = customerRepository.save(c2);

        KYC kyc2 = new KYC();
        kyc2.setCustomer(c2);
        kyc2.setStatus(KYCStatus.VERIFIED);
        kyc2.setDocumentType("INE_RFC");
        kyc2.setDocumentNumber(c2.getTaxId());
        kycRepository.save(kyc2);

        // 3. Customer 3: Comercializadora Alodiga S.A. de C.V. (Persona Jurídica)
        Customer c3 = new Customer();
        c3.setCustomerType(CustomerType.PERSONA_JURIDICA);
        c3.setFullName("Comercializadora Alodiga S.A. de C.V.");
        c3.setBusinessName("Comercializadora Alodiga S.A. de C.V.");
        c3.setPhoneNumber("+525512345678");
        c3.setEmail("tesoreria@alodiga.com.mx");
        c3.setTaxId("CAL120101AB9");
        c3.setLegalRepresentativeName("Maria Lopez Hernandez");
        c3.setLegalRepresentativeTaxId("LOHM750620ABC");
        c3 = customerRepository.save(c3);

        KYC kyc3 = new KYC();
        kyc3.setCustomer(c3);
        kyc3.setStatus(KYCStatus.VERIFIED);
        kyc3.setDocumentType("ACTA_CONSTITUTIVA_RFC_MORAL");
        kyc3.setDocumentNumber(c3.getTaxId());
        kycRepository.save(kyc3);

        // Fetch products
        CardProduct debitProd = cardProductRepository.findByProductCode("PROD-ALODIGA-DEB").orElse(cardProductRepository.findAll().get(0));
        CardProduct prepaidProd = cardProductRepository.findByProductCode("PROD-ALOCASH-PRE").orElse(cardProductRepository.findAll().get(0));
        CardProduct creditProd = cardProductRepository.findByProductCode("PROD-ALOCAS-CRE").orElse(cardProductRepository.findAll().get(0));

        // Issue Card 1: Carlos Rodriguez -> Tarjeta Alodiga Débito Core
        Card card1 = new Card();
        card1.setCustomer(c1);
        card1.setProduct(debitProd);
        card1.setEmbossedName("CARLOS RODRIGUEZ");
        card1.setLast4("1234");
        card1.setStatus(CardStatus.ACTIVE);
        card1.setCardCategory(CardCategory.PHYSICAL);
        card1.setExpiryDate(LocalDate.now().plusYears(3));
        card1 = cardRepository.save(card1);

        // Administrador de Tarjetas: promoción asignada específicamente al cliente/tarjeta
        if (cashbackSupermercados != null) {
            card1.assignPromotion(cashbackSupermercados);
            card1 = cardRepository.save(card1);
        }

        LedgerAccount acc1 = new LedgerAccount();
        acc1.setCard(card1);
        acc1.setCurrency("USD");
        acc1 = ledgerAccountRepository.save(acc1);
        ledgerEntryRepository.save(new LedgerEntry(acc1, EntryType.CREDIT, new BigDecimal("1500"), "Saldo Inicial Depósito Débito Core"));

        // Issue Card 2: Carlos Rodriguez -> Tarjeta Alocash Prepago USD
        Card card2 = new Card();
        card2.setCustomer(c1);
        card2.setProduct(prepaidProd);
        card2.setEmbossedName("CARLOS RODRIGUEZ");
        card2.setLast4("9999");
        card2.setStatus(CardStatus.ACTIVE);
        card2.setCardCategory(CardCategory.VIRTUAL);
        card2.setExpiryDate(LocalDate.now().plusYears(2));
        card2 = cardRepository.save(card2);

        LedgerAccount acc2 = new LedgerAccount();
        acc2.setCard(card2);
        acc2.setCurrency("USD");
        acc2 = ledgerAccountRepository.save(acc2);
        ledgerEntryRepository.save(new LedgerEntry(acc2, EntryType.CREDIT, new BigDecimal("500"), "Carga Inicial Billetera Prepago Alocash"));

        // Issue Card 3: Nguyen Van A -> Tarjeta Alodiga Débito Core (Multiple clients using same product!)
        Card card3 = new Card();
        card3.setCustomer(c2);
        card3.setProduct(debitProd);
        card3.setEmbossedName("NGUYEN VAN A");
        card3.setLast4("5555");
        card3.setStatus(CardStatus.ACTIVE);
        card3.setCardCategory(CardCategory.PHYSICAL);
        card3.setExpiryDate(LocalDate.now().plusYears(3));
        card3 = cardRepository.save(card3);

        LedgerAccount acc3 = new LedgerAccount();
        acc3.setCard(card3);
        acc3.setCurrency("USD");
        acc3 = ledgerAccountRepository.save(acc3);
        ledgerEntryRepository.save(new LedgerEntry(acc3, EntryType.CREDIT, new BigDecimal("3000"), "Apertura Cuenta Nómina Alodiga"));

        // Issue Card 4: Nguyen Van A -> Tarjeta Alocas Crédito Gold
        Card card4 = new Card();
        card4.setCustomer(c2);
        card4.setProduct(creditProd);
        card4.setEmbossedName("NGUYEN VAN A");
        card4.setLast4("7777");
        card4.setStatus(CardStatus.ACTIVE);
        card4.setCardCategory(CardCategory.PHYSICAL);
        card4.setExpiryDate(LocalDate.now().plusYears(4));
        card4.setCreditLimit(new BigDecimal("5000"));
        // Administrador de Tarjetas: límites transaccionales personalizados (override del producto)
        card4.setPerTransactionLimit(new BigDecimal("1500"));
        card4.setDailyLimitOverride(new BigDecimal("4000"));
        card4.setInternationalPurchasesEnabled(true);
        if (bonoVipCredito != null) {
            card4.assignPromotion(bonoVipCredito);
        }
        card4 = cardRepository.save(card4);

        LedgerAccount acc4 = new LedgerAccount();
        acc4.setCard(card4);
        acc4.setCurrency("USD");
        acc4 = ledgerAccountRepository.save(acc4);
        ledgerEntryRepository.save(new LedgerEntry(acc4, EntryType.CREDIT, new BigDecimal("5000"), "Límite de Crédito Aprobado"));

        // Issue Card 5: Comercializadora Alodiga S.A. de C.V. -> Tarjeta empresarial (Persona Jurídica)
        Card card5 = new Card();
        card5.setCustomer(c3);
        card5.setProduct(debitProd);
        card5.setEmbossedName("COMERCIALIZADORA ALODIGA SA DE CV");
        card5.setLast4("4321");
        card5.setStatus(CardStatus.ACTIVE);
        card5.setCardCategory(CardCategory.PHYSICAL);
        card5.setExpiryDate(LocalDate.now().plusYears(3));
        // Administrador de Tarjetas: límites y controles ajustados para uso empresarial
        card5.setPerTransactionLimit(new BigDecimal("3000"));
        card5.setDailyLimitOverride(new BigDecimal("10000"));
        card5.setWeeklyLimitOverride(new BigDecimal("40000"));
        card5.setMonthlyLimitOverride(new BigDecimal("120000"));
        card5.setAtmWithdrawalsEnabled(false);
        card5 = cardRepository.save(card5);

        LedgerAccount acc5 = new LedgerAccount();
        acc5.setCard(card5);
        acc5.setCurrency("USD");
        acc5 = ledgerAccountRepository.save(acc5);
        ledgerEntryRepository.save(new LedgerEntry(acc5, EntryType.CREDIT, new BigDecimal("15000"), "Apertura Cuenta Empresarial Alodiga"));

        log.info("Data seeding with realistic products & cards completed successfully!");
    }
}
