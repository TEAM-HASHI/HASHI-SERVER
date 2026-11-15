package org.sopt.hashi.support.domain;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.DateTimeException;
import java.util.Base64;
import org.sopt.hashi.shared.error.BusinessException;
import org.sopt.hashi.support.code.SupportErrorCode;

public record NoticeCursor(LocalDateTime publishedAt, Long id) {
    public static NoticeCursor parse(String value) {
        if (value == null) {
            return new NoticeCursor(null, null);
        }
        try {
            if (value.isBlank() || value.length() > 150) {
                throw new IllegalArgumentException();
            }
            String decoded = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
            String[] parts = decoded.split("\\|", -1);
            if (parts.length != 2) {
                throw new IllegalArgumentException();
            }
            LocalDateTime time = LocalDateTime.parse(parts[0]);
            long id = Long.parseLong(parts[1]);
            if (id < 1 || time.getNano() % 1000 != 0) {
                throw new IllegalArgumentException();
            }
            return new NoticeCursor(time, id);
        } catch (IllegalArgumentException | DateTimeException exception) {
            throw new BusinessException(SupportErrorCode.INVALID_CURSOR);
        }
    }

    public String encode() {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                (publishedAt + "|" + id).getBytes(StandardCharsets.UTF_8));
    }
}
