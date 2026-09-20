package org.sopt.hashi.shared.migration;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

class MigrationBoundaryTest {

    @Test
    void 공통_순회와_재시도는_JDK와_자기_패키지만_참조한다() {
        classes().that().resideInAPackage("org.sopt.hashi.shared.migration..")
                .should().onlyDependOnClassesThat()
                .resideInAnyPackage("java..", "org.sopt.hashi.shared.migration..")
                .check(new ClassFileImporter()
                        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                        .importPackages("org.sopt.hashi.shared.migration"));
    }
}
