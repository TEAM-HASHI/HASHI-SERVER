package org.sopt.hashi.admin.service;

import java.util.List;
import org.sopt.hashi.support.SupportPort;
import org.sopt.hashi.support.TermsCommand;
import org.sopt.hashi.support.TermsInfo;
import org.sopt.hashi.support.TermsType;
import org.sopt.hashi.support.TermsTypeInfo;
import org.springframework.stereotype.Service;

@Service
public class AdminTermsService {
    private final SupportPort port;
    public AdminTermsService(SupportPort port) { this.port = port; }
    public TermsInfo create(TermsCommand command) { return port.createTerms(command); }
    public TermsInfo update(Long id, TermsCommand command) { return port.updateTerms(id, command); }
    public TermsInfo publish(Long id) { return port.publishTerms(id); }
    public void delete(Long id) { port.deleteTerms(id); }
    public TermsInfo detail(Long id) { return port.findTermsByAdmin(id); }
    public List<TermsInfo> history(TermsType type, Long beforeId) { return port.findTermsHistory(type, beforeId); }
    public List<TermsTypeInfo> types() { return port.findTermsTypesByAdmin(); }
}
