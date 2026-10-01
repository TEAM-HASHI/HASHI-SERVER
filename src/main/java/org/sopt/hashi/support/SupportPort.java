package org.sopt.hashi.support;

import java.util.List;

/** 관리자 진입점이 사용하는 support 공개 facade. */
public interface SupportPort {
    NoticeInfo createNotice(NoticeCommand command);
    NoticeInfo updateNotice(Long id, NoticeCommand command);
    NoticeInfo publishNotice(Long id);
    void deleteNotice(Long id);
    NoticeInfo findNoticeByAdmin(Long id);
    List<NoticeInfo> findNoticesByAdmin(Long beforeId);
}
