package org.sopt.hashi.media.service;

import org.sopt.hashi.media.domain.MediaPurpose;
import org.sopt.hashi.media.internal.backfill.LegacyImageSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MediaBackfillReservationService {

    private final MediaBackfillTransactionService transactionService;

    public MediaBackfillReservationService(MediaBackfillTransactionService transactionService) {
        this.transactionService = transactionService;
    }

    /** unique 경합 뒤에는 실패한 transaction을 재사용하지 않고 별도 조회로 승자를 찾는다. */
    @Transactional(propagation = Propagation.NEVER)
    public BackfillAssetSnapshot reserveOrReuse(MediaPurpose purpose, String identityHash, LegacyImageSource source) {
        try {
            return transactionService.reserve(purpose, identityHash, source);
        } catch (DataIntegrityViolationException exception) {
            return transactionService.findReservation(purpose, identityHash, source)
                    .orElseThrow(() -> exception);
        }
    }
}
