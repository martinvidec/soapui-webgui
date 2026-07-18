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
public class EventStreamService {

    public record LogEvent(long id, String html) {
    }

    static final int CAPACITY = 500;
    private static final String EVENT_NAME = "mocklog";

    private static final Logger log = LogManager.getLogger(EventStreamService.class);

    private final Map<String, Deque<LogEvent>> buffers = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> sequences = new ConcurrentHashMap<>();
    private final Map<String, List<SseEmitter>> subscribers = new ConcurrentHashMap<>();

    public void append(String streamKey, String html) {
        long id = sequences.computeIfAbsent(streamKey, k -> new AtomicLong()).incrementAndGet();
        Deque<LogEvent> buffer = buffers.computeIfAbsent(streamKey, k -> new ConcurrentLinkedDeque<>());
        buffer.addLast(new LogEvent(id, html));
        while (buffer.size() > CAPACITY) {
            buffer.pollFirst();
        }
        for (SseEmitter emitter : subscribers.getOrDefault(streamKey, List.of())) {
            try {
                emitter.send(SseEmitter.event().id(Long.toString(id)).name(EVENT_NAME).data(html));
            } catch (Exception e) {
                unsubscribe(streamKey, emitter);
            }
        }
    }

    public List<LogEvent> eventsAfter(String streamKey, long lastEventId) {
        return buffers.getOrDefault(streamKey, new ConcurrentLinkedDeque<>()).stream()
                .filter(event -> event.id() > lastEventId)
                .toList();
    }

    /** Öffnet einen SSE-Stream; gepufferte Events nach lastEventId werden sofort nachgespielt. */
    public SseEmitter subscribe(String streamKey, long lastEventId) {
        SseEmitter emitter = new SseEmitter(0L);
        emitter.onCompletion(() -> unsubscribe(streamKey, emitter));
        emitter.onTimeout(() -> unsubscribe(streamKey, emitter));
        emitter.onError(e -> unsubscribe(streamKey, emitter));
        for (LogEvent event : eventsAfter(streamKey, lastEventId)) {
            try {
                emitter.send(SseEmitter.event()
                        .id(Long.toString(event.id())).name(EVENT_NAME).data(event.html()));
            } catch (Exception e) {
                log.debug("SSE-Replay abgebrochen: {}", e.getMessage());
                return emitter;
            }
        }
        subscribers.computeIfAbsent(streamKey, k -> new CopyOnWriteArrayList<>()).add(emitter);
        return emitter;
    }

    public void clear(String streamKey) {
        buffers.remove(streamKey);
        sequences.remove(streamKey);
        List<SseEmitter> emitters = subscribers.remove(streamKey);
        if (emitters != null) {
            emitters.forEach(SseEmitter::complete);
        }
    }

    private void unsubscribe(String streamKey, SseEmitter emitter) {
        List<SseEmitter> list = subscribers.get(streamKey);
        if (list != null) {
            list.remove(emitter);
        }
    }
}
