package org.sopt.hashi.media.internal.reconciliation;

public enum MediaObjectLocation {

    ORIGINAL("media/originals/"),
    RENDITION("media/renditions/");

    private final String prefix;

    MediaObjectLocation(String prefix) {
        this.prefix = prefix;
    }

    public String prefix() {
        return prefix;
    }
}
