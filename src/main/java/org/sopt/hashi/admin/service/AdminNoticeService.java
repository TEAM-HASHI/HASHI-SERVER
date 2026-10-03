package org.sopt.hashi.admin.service;

import java.util.List;
import org.sopt.hashi.support.NoticeCommand;
import org.sopt.hashi.support.NoticeInfo;
import org.sopt.hashi.support.SupportPort;
import org.springframework.stereotype.Service;

/** 관리자 진입점. 공지 상태·media transaction은 support가 처리한다. */
@Service
public class AdminNoticeService {
    private final SupportPort port;
    public AdminNoticeService(SupportPort port) { this.port = port; }
    public NoticeInfo create(NoticeCommand command) { return port.createNotice(command); }
    public NoticeInfo update(Long id, NoticeCommand command) { return port.updateNotice(id, command); }
    public NoticeInfo publish(Long id) { return port.publishNotice(id); }
    public void delete(Long id) { port.deleteNotice(id); }
    public NoticeInfo detail(Long id) { return port.findNoticeByAdmin(id); }
    public List<NoticeInfo> list(Long beforeId) { return port.findNoticesByAdmin(beforeId); }
}
