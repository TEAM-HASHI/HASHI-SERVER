package org.sopt.hashi.admin.service;

import java.util.List;
import org.sopt.hashi.support.SupportPort;
import org.sopt.hashi.support.TermsCommand;
import org.sopt.hashi.admin.dto.AdminTermsResponse;
import org.sopt.hashi.admin.dto.AdminTermsListResponse;
import org.sopt.hashi.support.TermsType;
import org.sopt.hashi.admin.dto.AdminTermsTypeResponse;
import org.springframework.stereotype.Service;

@Service
public class AdminTermsService {
    private final SupportPort port;
    public AdminTermsService(SupportPort port) { this.port = port; }
    public AdminTermsResponse create(TermsCommand command) { return AdminTermsResponse.from(port.createTerms(command)); }
    public AdminTermsResponse update(Long id, TermsCommand command) { return AdminTermsResponse.from(port.updateTerms(id, command)); }
    public AdminTermsResponse publish(Long id) { return AdminTermsResponse.from(port.publishTerms(id)); }
    public void delete(Long id) { port.deleteTerms(id); }
    public AdminTermsResponse detail(Long id) { return AdminTermsResponse.from(port.findTermsByAdmin(id)); }
    public AdminTermsListResponse history(TermsType type, int page, int size) {
        return AdminTermsListResponse.from(port.findTermsHistory(type, page, size));
    }
    public List<AdminTermsTypeResponse> types() {
        return port.findTermsTypesByAdmin().stream().map(AdminTermsTypeResponse::from).toList();
    }
}
