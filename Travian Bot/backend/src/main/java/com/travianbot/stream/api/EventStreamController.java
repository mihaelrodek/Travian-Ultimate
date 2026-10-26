package com.travianbot.stream.api;

import com.travianbot.stream.SseEventBroadcaster;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Server-Sent Events endpoint (roadmap NOT4). The frontend opens one
 * {@code EventSource('/api/events/stream')} for the whole app; the backend
 * pushes named events ({@code bot-event}, {@code incoming-attack}) as they
 * happen so screens update live instead of on their poll interval.
 */
@RestController
@RequestMapping("/api/events")
public class EventStreamController {

    private final SseEventBroadcaster broadcaster;

    public EventStreamController(SseEventBroadcaster broadcaster) {
        this.broadcaster = broadcaster;
    }

    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        return broadcaster.subscribe();
    }
}
