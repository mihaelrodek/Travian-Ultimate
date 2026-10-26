package com.travianbot.notification;

import com.travianbot.incoming.domain.IncomingAttack;
import com.travianbot.incoming.service.ReturnCatchPlanner.ReturnCatchSuggestion;
import com.travianbot.village.domain.Village;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Fans each bot-event notification out to every registered {@link EventNotifier}
 * channel. Call sites (login, captcha, resource-overflow, incoming-attack) inject
 * this instead of any single channel, so enabling a new channel is purely additive.
 * <p>
 * Deliberately NOT an {@code EventNotifier} itself — that would make Spring inject
 * the dispatcher into its own delegate list and recurse. The per-channel methods
 * are already {@code @Async} + self-guarding, so this fan-out returns immediately
 * and one channel's failure can't affect another (each call is wrapped defensively
 * as a final backstop).
 */
@Component
public class NotificationDispatcher {

    private static final Logger log = LoggerFactory.getLogger(NotificationDispatcher.class);

    private final List<EventNotifier> channels;

    public NotificationDispatcher(List<EventNotifier> channels) {
        this.channels = channels;
    }

    public void notifyIncomingAttack(IncomingAttack attack) {
        fanOut("incoming-attack", n -> n.notifyIncomingAttack(attack));
    }

    public void notifySessionLost(Long accountId, String reason) {
        fanOut("session-lost", n -> n.notifySessionLost(accountId, reason));
    }

    public void notifyCaptcha(Long accountId, String reason) {
        fanOut("captcha", n -> n.notifyCaptcha(accountId, reason));
    }

    public void notifyReturnCatch(ReturnCatchSuggestion suggestion) {
        fanOut("return-catch", n -> n.notifyReturnCatch(suggestion));
    }

    public void notifyResourceOverflow(Long accountId, Village village, String store, String detail) {
        fanOut("resource-overflow", n -> n.notifyResourceOverflow(accountId, village, store, detail));
    }

    private void fanOut(String kind, java.util.function.Consumer<EventNotifier> call) {
        for (EventNotifier channel : channels) {
            try {
                call.accept(channel);
            } catch (RuntimeException e) {
                // Should be unreachable — channels swallow their own errors — but a
                // synchronous guard failure here must not stop the other channels.
                log.debug("Notifier {} threw on {} event: {}",
                        channel.getClass().getSimpleName(), kind, e.toString());
            }
        }
    }
}
