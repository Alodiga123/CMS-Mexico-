package bank.cardissuing.fraud.guild.application;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Everything about the connection to the industry antifraud systems is a property. */
@Component
@ConfigurationProperties(prefix = "guild")
@Getter
@Setter
public class GuildSettings {
    /** simulated (default) or http. */
    private String mode = "simulated";
    /** Our participant id at the guild (assigned at onboarding). */
    private String participantId = "CMS-MX-DEV";
    private String baseUrl = "https://gremio.example.invalid/antifraude";
    private String apiKey = "";
    /** Shared secret for the HMAC-SHA256 request signature. */
    private String hmacSecret = "";
    /** Name of a Spring SSL bundle (spring.ssl.bundle.*) holding our client certificate for mTLS; empty = no mTLS. */
    private String sslBundle = "";
    private int timeoutMs = 800;
    /** When the guild cannot be reached: true = treat as not listed (authorize), false = treat as listed (decline). */
    private boolean failOpen = true;
    /** Hours an SVL answer stays valid before the guild is asked again. */
    private int verificationCacheHours = 24;
    /** Ask the guild's list during authorization (at most once per cache window per card / merchant). */
    private boolean verifyOnAuthorization = true;
    private int outboxMaxAttempts = 10;
    private long outboxIntervalMs = 30_000;
    private long inboundIntervalMs = 300_000;
    private long deadlineIntervalMs = 3_600_000;
    /** SNA: business days to close an alert before the loss is assumed to be ours. */
    private int closeBusinessDays = 10;
    /** Enumeration attack: natural days to give the formal answer. */
    private int enumerationResponseDays = 5;
    /** Block cards / merchants named by inbound alerts automatically. */
    private boolean autoActOnInbound = true;
}
