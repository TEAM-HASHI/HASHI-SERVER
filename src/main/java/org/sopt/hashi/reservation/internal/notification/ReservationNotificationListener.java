package org.sopt.hashi.reservation.internal.notification;

import org.sopt.hashi.reservation.event.ReservationRequestedEvent;
import org.springframework.scheduling.annotation.Async;
import org.springframework.transaction.event.TransactionalEventListener;

class ReservationNotificationListener {

    private final ReservationWebhookSender sender;

    ReservationNotificationListener(ReservationWebhookSender sender) {
        this.sender = sender;
    }

    @Async(ReservationNotificationConfig.EXECUTOR)
    @TransactionalEventListener(id = "reservation-requested-slack-v1")
    public void notifySlack(ReservationRequestedEvent event) {
        sender.sendSlack(event);
    }

    @Async(ReservationNotificationConfig.EXECUTOR)
    @TransactionalEventListener(id = "reservation-requested-discord-v1")
    public void notifyDiscord(ReservationRequestedEvent event) {
        sender.sendDiscord(event);
    }
}
