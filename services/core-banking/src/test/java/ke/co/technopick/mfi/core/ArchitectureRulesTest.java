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
        noClasses().that().resideInAnyPackage("..accounting..", "..lending..", "..savings..", "..canonical..", "..regulatory..")
                .should().dependOnClassesThat().haveFullyQualifiedName("java.lang.Double")
                .orShould().dependOnClassesThat().haveFullyQualifiedName("java.lang.Float")
                .check(CORE);
        noFields().that().areDeclaredInClassesThat()
                .resideInAnyPackage("..accounting..", "..lending..", "..savings..", "..canonical..", "..regulatory..")
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

    /**
     * The OpenAPI generator names schemas by simple class name, so two different API types with
     * the same simple name silently collapse into one schema and the contract describes the wrong
     * shape. Every type reachable from a REST controller must therefore have a unique simple name.
     */
    @Test
    void apiTypesHaveUniqueSimpleNames() {
        java.util.Map<String, java.util.Set<String>> byName = new java.util.TreeMap<>();
        java.util.Set<Class<?>> seen = new java.util.HashSet<>();
        CORE.stream()
            .filter(c -> c.isAnnotatedWith("org.springframework.web.bind.annotation.RestController"))
            .map(com.tngtech.archunit.core.domain.JavaClass::reflect)
            .forEach(controller -> {
                for (java.lang.reflect.Method m : controller.getDeclaredMethods()) {
                    if (java.lang.reflect.Modifier.isPublic(m.getModifiers())) {
                        collect(m.getGenericReturnType(), byName, seen);
                        for (java.lang.reflect.Type t : m.getGenericParameterTypes()) {
                            collect(t, byName, seen);
                        }
                    }
                }
            });
        java.util.Map<String, java.util.Set<String>> clashes = new java.util.TreeMap<>();
        byName.forEach((name, classes) -> {
            if (classes.size() > 1) {
                clashes.put(name, classes);
            }
        });
        org.junit.jupiter.api.Assertions.assertTrue(clashes.isEmpty(), "API schema name clashes: " + clashes);
        org.junit.jupiter.api.Assertions.assertTrue(byName.size() > 10, "expected to find the API types");
    }

    private static void collect(java.lang.reflect.Type type, java.util.Map<String, java.util.Set<String>> byName,
                                java.util.Set<Class<?>> seen) {
        if (type instanceof java.lang.reflect.ParameterizedType p) {
            for (java.lang.reflect.Type a : p.getActualTypeArguments()) {
                collect(a, byName, seen);
            }
            collect(p.getRawType(), byName, seen);
            return;
        }
        if (!(type instanceof Class<?> c) || !c.getName().startsWith("ke.co.technopick.mfi") || !seen.add(c)) {
            return;
        }
        byName.computeIfAbsent(c.getSimpleName(), k -> new java.util.TreeSet<>()).add(c.getName());
        if (c.isRecord()) {
            for (java.lang.reflect.RecordComponent rc : c.getRecordComponents()) {
                collect(rc.getGenericType(), byName, seen);
            }
        }
    }

    /**
     * Every endpoint states who may call it (ADR-0033): a role check on the method or on its controller.
     * With member tokens in the system, an endpoint left at "any authenticated caller" is not acceptable.
     */
    @Test
    void everyEndpointHasARoleCheck() {
        com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods()
                .that().areAnnotatedWith(org.springframework.web.bind.annotation.GetMapping.class)
                .or().areAnnotatedWith(org.springframework.web.bind.annotation.PostMapping.class)
                .or().areAnnotatedWith(org.springframework.web.bind.annotation.PutMapping.class)
                .or().areAnnotatedWith(org.springframework.web.bind.annotation.DeleteMapping.class)
                .or().areAnnotatedWith(org.springframework.web.bind.annotation.PatchMapping.class)
                .or().areAnnotatedWith(org.springframework.web.bind.annotation.RequestMapping.class)
                .should(new com.tngtech.archunit.lang.ArchCondition<com.tngtech.archunit.core.domain.JavaMethod>("have @PreAuthorize on the method or its class") {
                    @Override
                    public void check(com.tngtech.archunit.core.domain.JavaMethod method, com.tngtech.archunit.lang.ConditionEvents events) {
                        Class<org.springframework.security.access.prepost.PreAuthorize> pre =
                                org.springframework.security.access.prepost.PreAuthorize.class;
                        if (!method.isAnnotatedWith(pre) && !method.getOwner().isAnnotatedWith(pre)) {
                            events.add(com.tngtech.archunit.lang.SimpleConditionEvent.violated(method,
                                    method.getFullName() + " has no @PreAuthorize"));
                        }
                    }
                })
                .check(CORE);
    }
}
