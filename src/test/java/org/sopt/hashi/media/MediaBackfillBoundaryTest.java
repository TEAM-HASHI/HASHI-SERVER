package org.sopt.hashi.media;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

class MediaBackfillBoundaryTest {

    private static final String BACKFILL_API = "org\\.sopt\\.hashi\\.media\\.MediaBackfill.*";
    private static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("org.sopt.hashi");

    @Test
    void media_내부와_소유_도메인_migration만_backfill_계약에_의존한다() {
        noClasses().that().resideOutsideOfPackages(
                        "org.sopt.hashi.media..", "org.sopt.hashi.restaurant.migration..",
                        "org.sopt.hashi.user.migration..", "org.sopt.hashi.magazine.migration..",
                        "org.sopt.hashi.review.migration..")
                .should().dependOnClassesThat().haveNameMatching(BACKFILL_API)
                .check(CLASSES);
    }

    @Test
    void web_진입점은_backfill_계약을_사용하지_않는다() {
        noClasses().that().resideInAPackage("..web..")
                .should().dependOnClassesThat().haveNameMatching(BACKFILL_API)
                .check(CLASSES);
    }
}
