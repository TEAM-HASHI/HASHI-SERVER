package org.sopt.hashi.support.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.util.List;
import org.sopt.hashi.shared.error.BusinessException;
import org.sopt.hashi.support.NoticeBlock;
import org.sopt.hashi.support.NoticeCommand;
import org.sopt.hashi.support.code.SupportErrorCode;
import org.springframework.stereotype.Component;

@Component
public class NoticeContentCodec {
    private final ObjectMapper mapper;

    public NoticeContentCodec(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public String validateAndEncode(NoticeCommand command) {
        if (command == null || command.title() == null || command.title().isBlank()
                || command.title().length() > 100 || command.body() == null || command.body().isEmpty()
                || command.body().size() > 1000 || command.imageAssetIds() == null
                || command.imageAssetIds().size() > 10
                || command.imageAssetIds().stream().anyMatch(java.util.Objects::isNull)
                || command.imageAssetIds().stream().distinct().count() != command.imageAssetIds().size()) {
            throw invalid();
        }
        int length = 0;
        boolean hasText = false;
        for (NoticeBlock block : command.body()) {
            if (block == null || block.type() == null || block.items() == null || block.items().isEmpty()
                    || block.items().size() > 1000
                    || (block.type() == NoticeBlock.Type.PARAGRAPH && block.items().size() != 1)) {
                throw invalid();
            }
            for (List<NoticeBlock.Span> item : block.items()) {
                if (item == null || item.isEmpty() || item.size() > 1000) {
                    throw invalid();
                }
                for (NoticeBlock.Span span : item) {
                    if (span == null || span.text() == null || span.text().isEmpty()) {
                        throw invalid();
                    }
                    hasText |= !span.text().isBlank();
                    length += span.text().length();
                    if (span.href() != null) {
                        requireSafeLink(span.href());
                        length += span.href().length();
                    }
                    if (length > 10000) {
                        throw invalid();
                    }
                }
            }
        }
        if (!hasText) {
            throw invalid();
        }
        try {
            return mapper.writeValueAsString(command.body());
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("notice encoding failed", exception);
        }
    }

    public List<NoticeBlock> decode(String value) {
        try {
            return mapper.readValue(value, new TypeReference<List<NoticeBlock>>() {});
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("stored notice body is invalid", exception);
        }
    }

    private void requireSafeLink(String href) {
        try {
            if (href.length() > 2000 || href.contains("\\") || href.chars().anyMatch(c -> c <= 32 || c == 127)) {
                throw invalid();
            }
            URI uri = URI.create(href);
            boolean internal = href.startsWith("/") && !href.startsWith("//") && uri.getRawAuthority() == null;
            boolean external = ("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                    && uri.getHost() != null && uri.getUserInfo() == null;
            if (!internal && !external) {
                throw invalid();
            }
        } catch (IllegalArgumentException exception) {
            throw invalid();
        }
    }

    private BusinessException invalid() {
        return new BusinessException(SupportErrorCode.INVALID_NOTICE);
    }
}
