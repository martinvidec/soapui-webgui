package de.soapuiweb.service;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Request-Log laufender Mocks (FA-12): pro Mock ein Ring-Puffer der letzten
 * {@value #CAPACITY} Events plus SSE-Fanout. Reconnects spielen über die
 * Event-ID (Last-Event-ID-Header) verpasste Events aus dem Puffer nach.
 */
@Service
public class MockLogService {

    public record LogEvent(long id, String html) {
    }

    static final int CAPACITY = 500;
    private static final String EVENT_NAME = "mocklog";

    private static final Logger log = LogManager.getLogger(MockLogService.class);

    private final Map<String, Deque<LogEvent>> buffers = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> sequences = new ConcurrentHashMap<>();
    private final Map<String, List<SseEmitter>> subscribers = new ConcurrentHashMap<>();

    public void append(String mockId, String html) {
        long id = sequences.computeIfAbsent(mockId, k -> new AtomicLong()).incrementAndGet();
        Deque<LogEvent> buffer = buffers.computeIfAbsent(mockId, k -> new ConcurrentLinkedDeque<>());
        buffer.addLast(new LogEvent(id, html));
        while (buffer.size() > CAPACITY) {
            buffer.pollFirst();
        }
        for (SseEmitter emitter : subscribers.getOrDefault(mockId, List.of())) {
            try {
                emitter.send(SseEmitter.event().id(Long.toString(id)).name(EVENT_NAME).data(html));
            } catch (Exception e) {
                unsubscribe(mockId, emitter);
            }
        }
    }

    public List<LogEvent> eventsAfter(String mockId, long lastEventId) {
        return buffers.getOrDefault(mockId, new ConcurrentLinkedDeque<>()).stream()
                .filter(event -> event.id() > lastEventId)
                .toList();
    }

    /** Öffnet einen SSE-Stream; gepufferte Events nach lastEventId werden sofort nachgespielt. */
    public SseEmitter subscribe(String mockId, long lastEventId) {
        SseEmitter emitter = new SseEmitter(0L);
        emitter.onCompletion(() -> unsubscribe(mockId, emitter));
        emitter.onTimeout(() -> unsubscribe(mockId, emitter));
        emitter.onError(e -> unsubscribe(mockId, emitter));
        for (LogEvent event : eventsAfter(mockId, lastEventId)) {
            try {
                emitter.send(SseEmitter.event()
                        .id(Long.toString(event.id())).name(EVENT_NAME).data(event.html()));
            } catch (Exception e) {
                log.debug("SSE-Replay abgebrochen: {}", e.getMessage());
                return emitter;
            }
        }
        subscribers.computeIfAbsent(mockId, k -> new CopyOnWriteArrayList<>()).add(emitter);
        return emitter;
    }

    public void clear(String mockId) {
        buffers.remove(mockId);
        sequences.remove(mockId);
        List<SseEmitter> emitters = subscribers.remove(mockId);
        if (emitters != null) {
            emitters.forEach(SseEmitter::complete);
        }
    }

    private void unsubscribe(String mockId, SseEmitter emitter) {
        List<SseEmitter> list = subscribers.get(mockId);
        if (list != null) {
            list.remove(emitter);
        }
    }
}
