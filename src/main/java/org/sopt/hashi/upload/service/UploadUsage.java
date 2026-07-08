package org.sopt.hashi.upload.service;

import java.util.Arrays;
import java.util.Optional;

public enum UploadUsage {

    PROFILE("profile", "profiles"),
    REVIEW("review", "reviews"),
    RESTAURANT("restaurant", "restaurants"),
    RESTAURANT_MENU("restaurant-menu", "restaurant-menus"),
    MAGAZINE("magazine", "magazines");

    private final String value;
    private final String directory;

    UploadUsage(String value, String directory) {
        this.value = value;
        this.directory = directory;
    }

    public String directory() {
        return directory;
    }

    public static Optional<UploadUsage> from(String value) {
        return Arrays.stream(values())
                .filter(usage -> usage.value.equals(value))
                .findFirst();
    }
}
