package org.sopt.hashi.magazine.service;

import lombok.extern.slf4j.Slf4j;
import org.sopt.hashi.auth.CurrentUserProvider;
import org.sopt.hashi.magazine.code.MagazineErrorCode;
import org.sopt.hashi.magazine.code.MagazineSuccessCode;
import org.sopt.hashi.magazine.domain.MagazineMetaRepository;
import org.sopt.hashi.magazine.domain.MagazineReactionRepository;
import org.sopt.hashi.magazine.domain.MagazineReactionType;
import org.sopt.hashi.magazine.domain.MagazineRepository;
import org.sopt.hashi.magazine.dto.MagazineLikeResponse;
import org.sopt.hashi.magazine.dto.MagazineLikeResult;
import org.sopt.hashi.shared.error.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 매거진 좋아요 등록·취소(MAG-002). 클라이언트가 낙관적으로 갱신한 뒤 호출하므로 두 API 모두 멱등이다 —
 * 이미 좋아요인 상태의 등록, 좋아요가 아닌 상태의 취소는 에러가 아니라 현재 상태를 그대로 돌려주되,
 * 실제로 상태가 바뀌었는지는 성공 코드로 구분한다(LIKE_CREATED/LIKE_ALREADY_CREATED 등). 에러(409)로 내리지 않는 이유는
 * 재시도·연타로 같은 요청이 두 번 와도 클라이언트가 실패로 보고 하트를 원복하지 않게 하기 위함이다.
 * 리액션 행과 meta_magazine 카운터는 같은 트랜잭션에서 바뀐다 — 함께 커밋되거나 함께 롤백되므로 둘이 어긋나는
 * 상태가 없고, 응답의 likeCount는 커밋될 실제 값이다. 카운터를 magazine이 아닌 meta_magazine에 둔 덕에
 * 리액션 INSERT가 잡는 magazine 행 FK 부모 S 락과 카운터 UPDATE의 X 락이 같은 행에서 만나지 않아 교착이 없다.
 * 격리 수준을 READ COMMITTED로 낮춘 이유: 기본(REPEATABLE READ)에서는 INSERT의 유니크 중복 검사가
 * 갭 락(next-key)을 잡아, 같은 매거진에 동시 INSERT가 몰리면 이웃 행끼리 교착(MySQL 1213)한다.
 * 이 트랜잭션은 단일 행 upsert와 카운터 UPDATE뿐이라 갭 락이 필요 없다.
 */
@Slf4j
@Service
@Transactional(isolation = Isolation.READ_COMMITTED)
public class MagazineLikeService {

    private static final MagazineReactionType LIKE = MagazineReactionType.LIKE;

    private final MagazineRepository magazineRepository;
    private final MagazineReactionRepository magazineReactionRepository;
    private final MagazineMetaRepository magazineMetaRepository;
    private final CurrentUserProvider currentUserProvider;

    public MagazineLikeService(MagazineRepository magazineRepository,
                               MagazineReactionRepository magazineReactionRepository,
                               MagazineMetaRepository magazineMetaRepository,
                               CurrentUserProvider currentUserProvider) {
        this.magazineRepository = magazineRepository;
        this.magazineReactionRepository = magazineReactionRepository;
        this.magazineMetaRepository = magazineMetaRepository;
        this.currentUserProvider = currentUserProvider;
    }

    public MagazineLikeResult like(Long magazineId) {
        Long userId = currentUserProvider.currentUserId();
        validateMagazineExists(magazineId);

        // 새 행 → 없으면(이미 행 있음) INACTIVE 되살리기. 둘 다 0이면 이미 ACTIVE라 카운터를 건드리지 않는다.
        // 순서가 중요하다: 행이 없는 상태에서 UPDATE를 먼저 하면 유니크 인덱스 갭 락을 잡아 동시 INSERT와 교착한다.
        boolean activated = magazineReactionRepository.insertIfAbsent(magazineId, userId, LIKE) == 1
                || magazineReactionRepository.reactivate(magazineId, userId, LIKE) == 1;
        if (activated) {
            increaseLikeCount(magazineId);
            log.info("매거진 좋아요 등록. magazineId={} , userId={}", magazineId, userId);
        }
        return new MagazineLikeResult(
                activated ? MagazineSuccessCode.LIKE_CREATED : MagazineSuccessCode.LIKE_ALREADY_CREATED,
                new MagazineLikeResponse(magazineId, true, currentLikeCount(magazineId)));
    }

    public MagazineLikeResult unlike(Long magazineId) {
        Long userId = currentUserProvider.currentUserId();
        validateMagazineExists(magazineId);

        boolean deactivated = magazineReactionRepository.deactivate(magazineId, userId, LIKE) == 1;
        if (deactivated) {
            decreaseLikeCount(magazineId);
            log.info("매거진 좋아요 취소. magazineId={}, userId={}", magazineId, userId);
        }
        return new MagazineLikeResult(
                deactivated ? MagazineSuccessCode.LIKE_DELETED : MagazineSuccessCode.LIKE_ALREADY_DELETED,
                new MagazineLikeResponse(magazineId, false, currentLikeCount(magazineId)));
    }

    // 삭제된 매거진은 전역 필터로 걸러져 NOT_FOUND가 된다
    private void validateMagazineExists(Long magazineId) {
        if (!magazineRepository.existsById(magazineId)) {
            throw new BusinessException(MagazineErrorCode.NOT_FOUND);
        }
    }

    // 메타 행은 매거진 생성 트랜잭션에서 함께 만들어지므로 0행은 데이터 이상이다 — 예외로 트랜잭션째 롤백해 리액션만 남지 않게 한다
    private void increaseLikeCount(Long magazineId) {
        if (magazineMetaRepository.increaseLikeCount(magazineId) == 0) {
            throw new IllegalStateException("좋아요 수를 갱신할 매거진 메타 행이 없습니다. magazineId=" + magazineId);
        }
    }

    private void decreaseLikeCount(Long magazineId) {
        if (magazineMetaRepository.decreaseLikeCount(magazineId) == 0) {
            throw new IllegalStateException("좋아요 수를 갱신할 매거진 메타 행이 없습니다. magazineId=" + magazineId);
        }
    }

    private long currentLikeCount(Long magazineId) {
        return magazineMetaRepository.findLikeCountOrZero(magazineId);
    }
}
