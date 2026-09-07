package bank.cardissuing.card.application;

import bank.cardissuing.card.domain.Card;

import java.math.BigDecimal;

/**
 * Administrador de Tarjetas: creación de tarjetas, configuración de límites
 * transaccionales, controles por canal, asignación de promociones y PIN,
 * ligado siempre a un cliente (persona natural o jurídica).
 */
public interface CardAdminService {

    Card createCard(CreateCardCommand command);

    Card updateTransactionalLimits(Long cardId, TransactionalLimitsCommand command);

    Card updateChannelControls(Long cardId, ChannelControlsCommand command);

    Card assignPromotion(Long cardId, Long promotionId);

    Card removePromotion(Long cardId, Long promotionId);

    Card setPin(Long cardId, String pin);

    boolean verifyPin(Long cardId, String pin);

    record CreateCardCommand(
            Long customerId,
            Long productId,
            String embossedName,
            String cardCategory,
            BigDecimal initialDeposit) {
    }

    record TransactionalLimitsCommand(
            BigDecimal perTransactionLimit,
            BigDecimal dailyLimit,
            BigDecimal weeklyLimit,
            BigDecimal monthlyLimit,
            BigDecimal atmDailyLimit) {
    }

    record ChannelControlsCommand(
            Boolean onlinePurchasesEnabled,
            Boolean internationalPurchasesEnabled,
            Boolean contactlessEnabled,
            Boolean atmWithdrawalsEnabled) {
    }
}
