package org.sopt.hashi.restaurant.internal.map;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

class GeocodingBoundaryTest {

    @Test
    void provider와_후보_DTO는_restaurant_밖으로_노출하지_않는다() {
        var classes = new ClassFileImporter().withImportOption(new ImportOption.DoNotIncludeTests())
                .importPackages("org.sopt.hashi");
        noClasses().that().resideOutsideOfPackage("org.sopt.hashi.restaurant..")
                .should().dependOnClassesThat().resideInAPackage("org.sopt.hashi.restaurant.internal.map..")
                .check(classes);
    }
}
