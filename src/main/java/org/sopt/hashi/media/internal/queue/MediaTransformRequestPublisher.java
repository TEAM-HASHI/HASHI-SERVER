package org.sopt.hashi.media.internal.queue;

@FunctionalInterface
public interface MediaTransformRequestPublisher {

    void publish(MediaTransformRequest request);
}
