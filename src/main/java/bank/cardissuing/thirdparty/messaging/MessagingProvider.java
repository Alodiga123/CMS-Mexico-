package bank.cardissuing.thirdparty.messaging;

/**
 * The port to the messaging provider (SMS, WhatsApp, e-mail): one-time codes for the
 * step-up, notices about the plastic and about disputes. The CMS never talks to a
 * carrier directly; it hands the message to a provider contracted for that, and keeps
 * its own copy of what it asked for and what the provider answered.
 */
public interface MessagingProvider {

    enum Channel { SMS, WHATSAPP, EMAIL }

    record Message(Channel channel, String to, String text, String ref) { }

    /** The provider's reference and the state it reported (SENT, DELIVERED, ...). */
    record Delivery(String providerRef, String status) { }

    record Health(boolean up, String detail) { }

    String mode();

    Health health();

    /** Hands the message over. Throws on any failure so the outbox retries later. */
    Delivery send(Message m);
}
