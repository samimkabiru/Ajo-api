package com.theninjadev.ajoapi.verification;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Stand-in SMS sender: logs each message at INFO (readable locally and in the Render log
 * stream) and remembers the last message per number. The in-memory record exists so tests can
 * read the code that was "sent" without reaching into the database, where it is only stored
 * hashed.
 *
 * This implementation MUST be replaced by a real SMS provider before any production traffic.
 * It must never be the active bean once real users exist: it writes one-time codes to the logs.
 */
@Slf4j
@Component
public class LoggingSmsSender implements SmsSender {

    private final Map<String, String> lastMessageByPhone = new ConcurrentHashMap<>();

    @Override
    public void send(String e164Phone, String message) {
        log.info("SMS to {}: {}", e164Phone, message);
        lastMessageByPhone.put(e164Phone, message);
    }

    public Optional<String> lastMessageTo(String e164Phone) {
        return Optional.ofNullable(lastMessageByPhone.get(e164Phone));
    }

    public void clear() {
        lastMessageByPhone.clear();
    }
}
