package org.sopt.hashi;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

class ModularityTests {

    static final ApplicationModules modules = ApplicationModules.of(HashiApplication.class);

    @Test
    void verifiesModuleStructure() {
        modules.verify();
    }
}