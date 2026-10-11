package org.sopt.hashi.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.sopt.hashi.restaurant.RestaurantLocationPreviewCommand;

@Schema(description = "등록 전에 Google Places에서 매장을 찾을 입력. 검색 후 네 필드 중 하나라도 수정하면 다시 검색하세요")
public record PreviewRestaurantLocationRequest(
        @Schema(description = "최종 등록에 사용할 식당명", example = "도쿄역 이카루가")
        @NotBlank @Size(max = 100) String name,
        @Schema(description = "최종 등록에 사용할 현지 식당명. Places 검색에는 이 이름을 사용", example = "東京駅 斑鳩")
        @NotBlank @Size(max = 100) String localName,
        @Schema(description = "건물명·층을 포함한 표시 주소", example = "東京都千代田区丸の内1-9-1 東京駅一番街 B1F")
        @NotBlank @Size(max = 255) String address,
        @Schema(description = "검색용 별도 주소(선택). 생략/null이면 address를 사용하고 공백은 허용하지 않음")
        @Pattern(regexp = "(?s).*[^\\p{javaWhitespace}\\p{Z}].*") @Size(max = 255) String geocodingAddress) {
    public RestaurantLocationPreviewCommand toCommand() {
        return new RestaurantLocationPreviewCommand(name, localName, address, geocodingAddress);
    }
}
