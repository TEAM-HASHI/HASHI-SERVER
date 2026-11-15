package org.sopt.hashi.user.migration;

class UserProfileBackfillLeaseLostException extends RuntimeException {

    UserProfileBackfillLeaseLostException() {
        super("user profile backfill lease was lost");
    }
}
