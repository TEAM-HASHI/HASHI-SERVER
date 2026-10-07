package org.sopt.hashi.magazine.domain;

import java.util.List;
import java.util.UUID;

/**
 * {@link Magazine#planCardNewsReplacement}가 세운 카드뉴스 전체 교체 계획.
 * 계획과 반영을 나눈 이유: 새로 들어오는 asset과 빠지는 asset을 media가 먼저 확인(claim·retire)한 뒤에만
 * 애그리거트를 바꾸기 위함이다. 먼저 바꾸면 media 조회 직전의 flush가 새 행을 INSERT해,
 * 이미 쓰이는 asset일 때 media의 거절보다 DB 유니크 제약 위반이 먼저 난다.
 */
public final class CardNewsReplacement {

    private final List<Item> items;
    private final List<UUID> addedAssetIds;
    private final List<UUID> removedAssetIds;

    CardNewsReplacement(List<Item> items, List<UUID> addedAssetIds, List<UUID> removedAssetIds) {
        this.items = List.copyOf(items);
        this.addedAssetIds = List.copyOf(addedAssetIds);
        this.removedAssetIds = List.copyOf(removedAssetIds);
    }

    /** 이번 교체로 새로 연결되는 asset — 요청한 순서대로. */
    public List<UUID> addedAssetIds() {
        return addedAssetIds;
    }

    /** 이번 교체로 빠지는 기존 카드뉴스의 asset — 기존 노출 순서대로. */
    public List<UUID> removedAssetIds() {
        return removedAssetIds;
    }

    List<Item> items() {
        return items;
    }

    /** 교체 후 한 자리 — 기존 행을 재사용하거나(created=false) 새 행을 만든다. */
    record Item(MagazineCardNews cardNews, int displayOrder, boolean created) {
    }
}
