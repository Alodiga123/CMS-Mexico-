package bank.cardissuing.card.domain;

/** Where an authorization comes from. Each can be switched off per card. */
public enum Channel {
    POS,
    ATM,
    ECOMMERCE,
    CONTACTLESS
}
