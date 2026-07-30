package com.rigour.tenant.iam;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/** 防止重构后的包名只停留在目录表面。 */
class IamPackageArchitectureTest {

    private static final JavaClasses IAM_CLASSES = new ClassFileImporter()
            .importPackages("com.rigour.tenant.iam");

    @Test
    void domainDoesNotDependOnOuterLayersOrFrameworks() {
        noClasses().that().resideInAPackage("..domain..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "com.rigour.tenant.iam.api..",
                        "com.rigour.tenant.iam.application..",
                        "com.rigour.tenant.iam.infrastructure..",
                        "org.springframework..",
                        "org.mybatis..",
                        "com.baomidou.mybatisplus.."
                )
                .check(IAM_CLASSES);
    }

    @Test
    void applicationDoesNotDependOnApiOrInfrastructure() {
        noClasses().that().resideInAPackage("..application..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "com.rigour.tenant.iam.api..",
                        "com.rigour.tenant.iam.infrastructure.."
                )
                .check(IAM_CLASSES);
    }

    @Test
    void apiDoesNotAccessPersistenceImplementation() {
        noClasses().that().resideInAPackage("..api..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "..infrastructure.persistence..",
                        "org.apache.ibatis..",
                        "com.baomidou.mybatisplus.."
                )
                .check(IAM_CLASSES);
    }

    @Test
    void familiarTypeNamesStayInTheirOwningPackages() {
        classes().that().haveSimpleNameEndingWith("Controller")
                .should().resideInAPackage("..api.controller..")
                .check(IAM_CLASSES);
        classes().that().haveSimpleNameEndingWith("Service")
                .should().resideInAPackage("..application.service..")
                .check(IAM_CLASSES);
        classes().that().haveSimpleNameEndingWith("Mapper")
                .should().resideInAPackage("..infrastructure.persistence.mapper..")
                .allowEmptyShould(true)
                .check(IAM_CLASSES);
    }
}
