package org.sopt.hashi.support;

import java.util.List;
import java.util.UUID;

/** 관리자 저장 명령. 수정은 전체 교체이며 imageAssetIds 순서가 표시 순서다. */
public record NoticeCommand(String title, List<NoticeBlock> body, List<UUID> imageAssetIds) {}
