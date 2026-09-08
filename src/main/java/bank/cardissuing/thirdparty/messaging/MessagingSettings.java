package bank.cardissuing.thirdparty.messaging;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "thirdparty.messaging")
@Getter
@Setter
public class MessagingSettings {
    /** simulated | http */
    private String mode = "simulated";
    private String baseUrl = "https://mensajeria.example.invalid/v1";
    private String apiKey = "";
    private String senderId = "CMS-MX";
    private int timeoutMs = 3000;
    private MessagingProvider.Channel defaultChannel = MessagingProvider.Channel.SMS;
    private int maxAttempts = 5;
    private long retryIntervalMs = 30000;
    /** Keep the one-time code out of our own copy of the message. */
    private boolean maskOtp = true;
}
