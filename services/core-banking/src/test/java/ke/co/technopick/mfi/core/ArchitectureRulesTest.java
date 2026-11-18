package ke.co.technopick.mfi.core;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

/**
 * Architecture rules from the Phase 0 design, section 3.
 * Requires the networked Maven build (ArchUnit); not run by the offline verification.
 */
class ArchitectureRulesTest {

    private static final JavaClasses CORE = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("ke.co.technopick.mfi");

    @Test
    void domainIsFrameworkFree() {
        noClasses().that().resideInAPackage("..domain..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "org.springframework..", "jakarta.persistence..", "org.hibernate..",
                        "org.apache.camel..", "org.jpos..", "java.net..", "java.sql..")
                .check(CORE);
    }

    @Test
    void noBinaryFloatingPointInMoneyCode() {
        noClasses().that().resideInAnyPackage("..accounting..", "..lending..", "..savings..", "..canonical..")
                .should().dependOnClassesThat().haveFullyQualifiedName("java.lang.Double")
                .orShould().dependOnClassesThat().haveFullyQualifiedName("java.lang.Float")
                .check(CORE);
        noFields().that().areDeclaredInClassesThat()
                .resideInAnyPackage("..accounting..", "..lending..", "..savings..", "..canonical..")
                .should().haveRawType(double.class)
                .orShould().haveRawType(float.class)
                .check(CORE);
    }

    @Test
    void lendingDoesNotReachIntoAccountingInternals() {
        noClasses().that().resideInAPackage("..lending..")
                .should().dependOnClassesThat().resideInAPackage("..accounting.persistence..")
                .check(CORE);
    }

    @Test
    void modulesAreFreeOfCycles() {
        slices().matching("ke.co.technopick.mfi.core.(*)..").should().beFreeOfCycles().check(CORE);
    }
}
