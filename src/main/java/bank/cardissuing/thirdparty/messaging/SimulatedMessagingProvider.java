package bank.cardissuing.thirdparty.messaging;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The provider for development and tests: delivers instantly, keeps the last messages
 * in memory so the console and the checks can see them, and can be switched off to
 * rehearse an outage. The production connection is {@link HttpMessagingProvider}.
 */
@Component
@ConditionalOnProperty(name = "thirdparty.messaging.mode", havingValue = "simulated", matchIfMissing = true)
@Slf4j
public class SimulatedMessagingProvider implements MessagingProvider {

    private final List<Message> sent = new CopyOnWriteArrayList<>();
    private final AtomicInteger seq = new AtomicInteger();
    private volatile boolean down;

    @Override public String mode() { return "simulated"; }

    @Override public Health health() { return new Health(!down, down ? "simulator switched off" : "simulator up"); }

    @Override
    public Delivery send(Message m) {
        if (down) throw new IllegalStateException("messaging simulator switched off");
        sent.add(m);
        if (sent.size() > 200) sent.remove(0);
        String ref = String.format("SIM-MSG-%06d", seq.incrementAndGet());
        log.info("[messaging-sim] {} to {}: {}", m.channel(), mask(m.to()), m.text());
        return new Delivery(ref, "DELIVERED");
    }

    public void setDown(boolean down) { this.down = down; }

    public boolean isDown() { return down; }

    public List<Message> sent() { return List.copyOf(sent); }

    static String mask(String to) {
        if (to == null || to.length() < 4) return "****";
        return "*".repeat(to.length() - 4) + to.substring(to.length() - 4);
    }
}
