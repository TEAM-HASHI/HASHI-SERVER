package org.sopt.hashi.support.service;

import java.util.List;
import org.sopt.hashi.support.NoticeCommand;
import org.sopt.hashi.support.NoticeInfo;
import org.sopt.hashi.support.SupportPort;
import org.springframework.stereotype.Component;

@Component
class SupportPortImpl implements SupportPort {
    private final NoticeService notices;

    SupportPortImpl(NoticeService notices) {
        this.notices = notices;
    }

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
    public List<NoticeInfo> findNoticesByAdmin(Long beforeId) { return notices.adminList(beforeId); }
}
