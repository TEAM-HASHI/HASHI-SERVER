package org.sopt.hashi.restaurant.internal.map.places;

final class PlacesFixtures {

    static final String API_KEY = "synthetic-server-key";
    static final String QUERY = "東京都 テスト + & # / % ?= 店";
    static final String PLACE_ID = "ChIJ_synthetic-place-1";
    static final String CANDIDATE = """
            {"id":"ChIJ_synthetic-place-1",
             "displayName":{"text":"Synthetic Restaurant","languageCode":"en"},
             "formattedAddress":"1-2-3 Test, Chiyoda City, Tokyo",
             "location":{"latitude":35.12345678901234567,"longitude":139.76543210987654321},
             "addressComponents":[
               {"longText":"Tokyo","shortText":"Tokyo","types":["administrative_area_level_1","political"]},
               {"longText":"Japan","shortText":"JP","types":["country","political"]}],
             "types":["restaurant","food"],
             "businessStatus":"OPERATIONAL",
             "attributions":[{"provider":"Synthetic Provider","providerUri":"https://provider.invalid/info"}],
             "googleMapsUri":"https://maps.google.com/?cid=synthetic"}
            """;
    static final String SEARCH_SUCCESS = "{\"places\":[" + CANDIDATE + "]}";

    private PlacesFixtures() {
    }
}
