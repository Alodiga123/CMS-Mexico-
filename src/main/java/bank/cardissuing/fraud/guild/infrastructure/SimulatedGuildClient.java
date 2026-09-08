package bank.cardissuing.fraud.guild.infrastructure;

import bank.cardissuing.fraud.guild.application.GuildClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A guild that lives in memory: lists you can populate, an inbound queue you can feed,
 * a switch to take it down. Used by default and by the end-to-end tests; the real
 * connection is {@link HttpGuildClient}.
 */
@Component
@ConditionalOnProperty(name = "guild.mode", havingValue = "simulated", matchIfMissing = true)
@Slf4j
public class SimulatedGuildClient implements GuildClient {

    private final Map<String, Hit> listedCards = new ConcurrentHashMap<>();
    private final Map<String, Hit> listedMerchants = new ConcurrentHashMap<>();
    private final List<Inbound> inbox = new CopyOnWriteArrayList<>();
    private final List<Outbound> sent = new CopyOnWriteArrayList<>();
    private final AtomicInteger seq = new AtomicInteger();
    private volatile boolean down;

    @Override public String mode() { return "simulated"; }

    @Override public Health health() { return new Health(!down, down ? "simulator switched off" : "simulator up"); }

    @Override
    public Hit verifyCard(String bin, String last4) {
        requireUp();
        return listedCards.getOrDefault(key(bin, last4), Hit.clean());
    }

    @Override
    public Hit verifyMerchant(String merchantId) {
        requireUp();
        return listedMerchants.getOrDefault(merchantId, Hit.clean());
    }

    @Override
    public String send(Outbound message) {
        requireUp();
        sent.add(message);
        String folio = "SNA-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd")) + "-" + String.format("%04d", seq.incrementAndGet());
        log.info("[guild-sim] sent {} for {} -> {}", message.type(), message.localRef(), folio);
        return folio;
    }

    @Override
    public List<Inbound> fetchInbound(LocalDateTime since) {
        requireUp();
        List<Inbound> out = new ArrayList<>();
        for (Inbound i : inbox) if (since == null || i.issuedAt().isAfter(since)) out.add(i);
        return out;
    }

    // ---- simulator controls ----

    public void listCard(String bin, String last4, String reason) {
        listedCards.put(key(bin, last4), new Hit(true, "SVL-" + seq.incrementAndGet(), reason, LocalDateTime.now()));
    }

    public void listMerchant(String merchantId, String reason) {
        listedMerchants.put(merchantId, new Hit(true, "SVL-" + seq.incrementAndGet(), reason, LocalDateTime.now()));
    }

    public Inbound pushInbound(String type, String bin, String last4, String merchantId, String merchantName, String description) {
        Inbound i = new Inbound("GRM-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd")) + "-" + String.format("%04d", seq.incrementAndGet()),
                type, bin, last4, merchantId, merchantName, description, LocalDateTime.now(), "SIMULATOR");
        inbox.add(i);
        return i;
    }

    public void setDown(boolean down) { this.down = down; }

    public boolean isDown() { return down; }

    public List<Outbound> sent() { return List.copyOf(sent); }

    private void requireUp() { if (down) throw new IllegalStateException("guild simulator is down"); }

    private static String key(String bin, String last4) { return (bin == null ? "" : bin) + "*" + (last4 == null ? "" : last4); }
}
