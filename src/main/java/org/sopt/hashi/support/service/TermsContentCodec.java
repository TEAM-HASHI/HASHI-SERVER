package org.sopt.hashi.support.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.sopt.hashi.shared.error.BusinessException;
import org.sopt.hashi.support.TermsClause;
import org.sopt.hashi.support.TermsCommand;
import org.sopt.hashi.support.code.SupportErrorCode;
import org.springframework.stereotype.Component;

@Component
public class TermsContentCodec {
    private final ObjectMapper mapper;
    public TermsContentCodec(ObjectMapper mapper) { this.mapper = mapper; }

    public String validateAndEncode(TermsCommand command) {
        if (command == null || command.type() == null || command.title() == null
                || command.title().isBlank() || command.title().length() > 100
                || command.version() == null || !command.version().matches("[A-Za-z0-9][A-Za-z0-9._-]{0,49}")
                || command.effectiveDate() == null || command.effectiveDate().getYear() < 1000
                || command.effectiveDate().getYear() > 9999
                || command.clauses() == null || command.clauses().isEmpty() || command.clauses().size() > 200) {
            throw invalid();
        }
        int total = 0;
        for (TermsClause clause : command.clauses()) {
            if (clause == null || clause.heading() == null || clause.heading().isBlank()
                    || clause.heading().length() > 100 || clause.content() == null || clause.content().isBlank()) {
                throw invalid();
            }
            total += clause.heading().length() + clause.content().length();
            if (total > 100000) { throw invalid(); }
        }
        try {
            return mapper.writeValueAsString(command.clauses());
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("terms encoding failed", exception);
        }
    }

    public List<TermsClause> decode(String json) {
        try {
            return mapper.readValue(json, new TypeReference<List<TermsClause>>() {});
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("stored terms clauses are invalid", exception);
        }
    }

    private BusinessException invalid() { return new BusinessException(SupportErrorCode.INVALID_TERMS); }
}
