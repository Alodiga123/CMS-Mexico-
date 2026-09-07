package bank.cardissuing.transaction.application;

import bank.cardissuing.card.application.CardControlsService;
import bank.cardissuing.card.domain.Card;
import bank.cardissuing.card.domain.CardControls;
import bank.cardissuing.card.domain.Channel;
import bank.cardissuing.transaction.domain.AuthorizationRequest;
import bank.cardissuing.transaction.domain.ResponseCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Optional;

/**
 * Per-card controls: is this channel on, is international allowed right now, is the
 * amount within the per-transaction cap. Checked before limits and before funds --
 * a switched-off channel should not even be counted, let alone reach the core.
 */
@Component
@RequiredArgsConstructor
public class ControlsPolicy {

    private final CardControlsService controls;

    public Optional<Breach> check(Card card, AuthorizationRequest request) {
        CardControls c = controls.forCard(card);

        Channel channel = request.channelOrDefault();
        if (!c.allows(channel)) {
            return Optional.of(new Breach(ResponseCode.NOT_PERMITTED, channel + " is disabled on this card"));
        }
        if (isInternational(card, request) && !c.allowsInternational(LocalDate.now())) {
            return Optional.of(new Breach(ResponseCode.NOT_PERMITTED,
                    "international transactions are disabled on this card (merchant country " + request.getCountryCode() + ")"));
        }
        if (c.getPerTransactionMax() != null && c.getPerTransactionMax().signum() > 0
                && request.getAmount().compareTo(c.getPerTransactionMax()) > 0) {
            return Optional.of(new Breach(ResponseCode.EXCEEDS_LIMIT,
                    "per-transaction max " + c.getPerTransactionMax() + " exceeded (requested " + request.getAmount() + ")"));
        }
        return Optional.empty();
    }

    /** International = the merchant's country is given and differs from the product's. No country, no product country: domestic. */
    static boolean isInternational(Card card, AuthorizationRequest request) {
        String merchant = request.getCountryCode();
        if (merchant == null || merchant.isBlank() || card.getProduct() == null) return false;
        String home = card.getProduct().getCountry();
        if (home == null || home.isBlank()) return false;
        return !normalize(home).equals(normalize(merchant));
    }

    /** Products store countries loosely ("MX", "USA", "🇺🇸 USA"); keep only letters, and map the common 3-letter forms. */
    private static String normalize(String s) {
        String letters = s.replaceAll("[^A-Za-z]", "").toUpperCase();
        return switch (letters) {
            case "USA" -> "US";
            case "MEX" -> "MX";
            default -> letters;
        };
    }
}
