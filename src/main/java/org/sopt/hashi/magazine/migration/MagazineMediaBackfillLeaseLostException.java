package org.sopt.hashi.magazine.migration;

class MagazineMediaBackfillLeaseLostException extends RuntimeException {

    MagazineMediaBackfillLeaseLostException() {
        super("magazine media backfill lease was lost");
    }
}
