package org.sopt.hashi.support.service;

import java.util.List;
import org.springframework.data.domain.Page;
import org.sopt.hashi.support.NoticeCommand;
import org.sopt.hashi.support.NoticeInfo;
import org.sopt.hashi.support.SupportPort;
import org.sopt.hashi.support.TermsCommand;
import org.sopt.hashi.support.TermsInfo;
import org.sopt.hashi.support.TermsType;
import org.sopt.hashi.support.TermsTypeInfo;
import org.springframework.stereotype.Component;

@Component
class SupportPortImpl implements SupportPort {
    private final NoticeService notices;

    private final TermsService terms;

    SupportPortImpl(NoticeService notices, TermsService terms) {
        this.notices = notices;
        this.terms = terms;
    }

    @Override
    public TermsInfo createTerms(TermsCommand command) { return terms.create(command); }
    @Override
    public TermsInfo updateTerms(Long id, TermsCommand command) { return terms.update(id, command); }
    @Override
    public TermsInfo publishTerms(Long id) { return terms.publish(id); }
    @Override
    public void deleteTerms(Long id) { terms.delete(id); }
    @Override
    public TermsInfo findTermsByAdmin(Long id) { return terms.adminDetail(id); }
    @Override
    public List<TermsInfo> findTermsHistory(TermsType type, Long beforeId) { return terms.adminHistory(type, beforeId); }
    @Override
    public List<TermsTypeInfo> findTermsTypesByAdmin() { return terms.adminTypes(); }
    @Override
    public NoticeInfo createNotice(NoticeCommand command) { return notices.create(command); }
    @Override
    public NoticeInfo updateNotice(Long id, NoticeCommand command) { return notices.update(id, command); }
    @Override
    public NoticeInfo publishNotice(Long id) { return notices.publish(id); }
    @Override
    public void deleteNotice(Long id) { notices.delete(id); }
    @Override
    public NoticeInfo findNoticeByAdmin(Long id) { return notices.adminDetail(id); }
    @Override
    public Page<NoticeInfo> findNoticesByAdmin(int page, int size) { return notices.adminList(page, size); }
}
