package org.sopt.hashi.restaurant.internal.map.google;

final class GeocodingFixtures {

    static final String API_KEY = "synthetic-server-key";
    static final String ADDRESS = "東京都 テスト + & # / % ?= 店";
    static final String CANDIDATE = """
            {"location":{"latitude":35.12345678901234567,"longitude":139.76543210987654321},
             "granularity":"ROOFTOP", "postalAddress":{"regionCode":"JP","administrativeArea":"東京都"},
             "addressComponents":[{"longText":"架空住所","shortText":"架空","types":["route"]},
                                  {"longText":"日本","shortText":"JP","types":["country","political"]}],
             "types":["street_address"], "formattedAddress":"must-not-be-retained"}
            """;
    static final String SUCCESS = "{\"results\":[" + CANDIDATE + "]}";

    private GeocodingFixtures() {
    }
}
