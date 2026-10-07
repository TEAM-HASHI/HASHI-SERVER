package org.sopt.hashi.support;

import org.springframework.data.domain.Page;

/** 관리자 진입점이 사용하는 support 공개 facade. */
public interface SupportPort {
    NoticeInfo createNotice(NoticeCommand command);
    NoticeInfo updateNotice(Long id, NoticeCommand command);
    NoticeInfo publishNotice(Long id);
    void deleteNotice(Long id);
    NoticeInfo findNoticeByAdmin(Long id);
    Page<NoticeSummaryInfo> findNoticesByAdmin(int page, int size);
}
