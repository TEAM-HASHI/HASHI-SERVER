package org.sopt.hashi.media.internal.spec;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.sopt.hashi.media.domain.ImageRole;
import org.sopt.hashi.media.domain.MediaPurpose;

public record MediaSpecDefinition(
        int version,
        String digest,
        Map<MediaPurpose, List<ImageRole>> purposeRoles,
        Map<ImageRole, MediaRoleSpec> roleSpecs
) {

    public MediaSpecDefinition {
        if (version < 1) {
            throw new IllegalArgumentException("media spec version must be positive");
        }
        if (digest == null || !digest.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("media spec digest is invalid");
        }
        Map<MediaPurpose, List<ImageRole>> copiedPurposeRoles =
                Map.copyOf(Objects.requireNonNull(purposeRoles));
        Map<ImageRole, MediaRoleSpec> copiedRoleSpecs =
                Map.copyOf(Objects.requireNonNull(roleSpecs));
        copiedPurposeRoles.values().stream()
                .flatMap(List::stream)
                .forEach(role -> {
                    if (!copiedRoleSpecs.containsKey(role)) {
                        throw new IllegalArgumentException(
                                "media purpose references an unknown role: " + role);
                    }
                });
        purposeRoles = copiedPurposeRoles;
        roleSpecs = copiedRoleSpecs;
    }

    public MediaSpecSnapshot snapshot() {
        return new MediaSpecSnapshot(version, digest);
    }

    public List<MediaExpectedRendition> expectedRenditions(
            MediaPurpose purpose, int sourceWidth, int sourceHeight) {
        List<ImageRole> roles = purposeRoles.get(purpose);
        if (roles == null || roles.isEmpty()) {
            throw new IllegalArgumentException("purpose is missing from the media spec");
        }
        List<MediaExpectedRendition> expected = new ArrayList<>();
        for (ImageRole role : roles) {
            MediaRoleSpec roleSpec = roleSpecs.get(role);
            roleSpec.selectFor(sourceWidth, sourceHeight).forEach(dimensions ->
                    expected.add(new MediaExpectedRendition(
                            role,
                            dimensions.width(),
                            dimensions.height()
                    )));
        }
        return List.copyOf(expected);
    }
}
