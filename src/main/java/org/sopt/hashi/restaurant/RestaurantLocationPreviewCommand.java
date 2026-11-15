package org.sopt.hashi.restaurant;

/** 등록 전 위치 확인에 사용한 입력. 최종 등록도 같은 네 필드를 보내야 한다. */
public record RestaurantLocationPreviewCommand(String name, String localName, String address,
                                                String geocodingAddress) {
    public static RestaurantLocationPreviewCommand from(AdminRestaurantCommand command) {
        return new RestaurantLocationPreviewCommand(command.name(), command.localName(), command.address(),
                command.geocodingAddress());
    }

    @Override
    public String toString() {
        return "RestaurantLocationPreviewCommand[redacted]";
    }
}
