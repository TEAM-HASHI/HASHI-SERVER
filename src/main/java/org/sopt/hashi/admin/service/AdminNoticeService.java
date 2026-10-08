package org.sopt.hashi.admin.service;

import org.sopt.hashi.admin.dto.AdminNoticeResponse;
import org.sopt.hashi.admin.dto.AdminNoticeListResponse;
import org.sopt.hashi.support.NoticeCommand;
import org.sopt.hashi.support.SupportPort;
import org.springframework.stereotype.Service;

/** 관리자 진입점. 공지 상태·media transaction은 support가 처리한다. */
@Service
public class AdminNoticeService {
    private final SupportPort port;
    public AdminNoticeService(SupportPort port) { this.port = port; }
    public AdminNoticeResponse create(NoticeCommand command) { return AdminNoticeResponse.from(port.createNotice(command)); }
    public AdminNoticeResponse update(Long id, NoticeCommand command) { return AdminNoticeResponse.from(port.updateNotice(id, command)); }
    public AdminNoticeResponse publish(Long id) { return AdminNoticeResponse.from(port.publishNotice(id)); }
    public void delete(Long id) { port.deleteNotice(id); }
    public AdminNoticeResponse detail(Long id) { return AdminNoticeResponse.from(port.findNoticeByAdmin(id)); }
    public AdminNoticeListResponse list(int page, int size) {
        return AdminNoticeListResponse.from(port.findNoticesByAdmin(page, size));
    }
}
