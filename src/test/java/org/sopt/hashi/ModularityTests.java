package org.sopt.hashi;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

class ModularityTests {

    static final ApplicationModules modules = ApplicationModules.of(HashiApplication.class);

    @Test
    void 모듈_경계와_순환의존_규칙을_검증한다() {
        modules.verify();
    }
}