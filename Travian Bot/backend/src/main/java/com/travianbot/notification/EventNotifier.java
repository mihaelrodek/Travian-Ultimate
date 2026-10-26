package com.travianbot.notification;

import com.travianbot.incoming.domain.IncomingAttack;
import com.travianbot.incoming.service.ReturnCatchPlanner.ReturnCatchSuggestion;
import com.travianbot.village.domain.Village;

/**
 * A push-notification channel (Discord, Telegram, …). Every bot event that is
 * worth paging the user about is declared here once; each channel implements
 * the delivery. Call sites depend on {@link NotificationDispatcher}, which
 * fans a single call out to every registered channel — so adding a channel is
 * a new {@code EventNotifier} bean and nothing else.
 * <p>
 * Implementations are expected to be self-guarding and fire-and-forget: each
 * method decides on its own whether its channel is enabled / configured / the
 * per-event toggle is on, and must never throw back into the caller (a failed
 * notification can't be allowed to break the bot action that triggered it).
 * The channel-specific "send a test message" action is deliberately NOT part
 * of this interface — it belongs to each channel's own settings controller.
 */
public interface EventNotifier {

    void notifyIncomingAttack(IncomingAttack attack);

    void notifySessionLost(Long accountId, String reason);

    void notifyCaptcha(Long accountId, String reason);

    void notifyReturnCatch(ReturnCatchSuggestion suggestion);

    void notifyResourceOverflow(Long accountId, Village village, String store, String detail);
}
