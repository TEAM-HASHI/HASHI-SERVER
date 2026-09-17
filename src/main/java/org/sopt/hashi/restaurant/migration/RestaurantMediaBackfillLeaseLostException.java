package org.sopt.hashi.restaurant.migration;

class RestaurantMediaBackfillLeaseLostException extends RuntimeException {

    RestaurantMediaBackfillLeaseLostException() {
        super("restaurant media backfill lease was lost");
    }
}
