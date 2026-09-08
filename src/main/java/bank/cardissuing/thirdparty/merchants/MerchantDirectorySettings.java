package bank.cardissuing.thirdparty.merchants;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** The merchant portal (acquiring backend) the console reads affiliated merchants from. */
@Component
@ConfigurationProperties(prefix = "thirdparty.merchant-portal")
@Getter
@Setter
public class MerchantDirectorySettings {
    private boolean enabled = true;
    /** Base URL of the acquiring API, e.g. http://localhost:4000/api/v1 */
    private String baseUrl = "http://localhost:4000/api/v1";
    /** Integration credential issued by the portal (X-API-Key) with the COMERCIOS role. */
    private String apiKey = "";
    private int timeoutMs = 2500;
    /** How long a successful listing is reused before asking the portal again. */
    private int cacheSeconds = 60;
}
