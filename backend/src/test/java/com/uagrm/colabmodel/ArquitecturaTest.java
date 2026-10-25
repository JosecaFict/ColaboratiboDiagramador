package com.uagrm.colabmodel;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Verifica el escenario de calidad EC-12: modularidad sin ciclos.
 *
 * Este test es la razon por la que podemos llamar a esto "monolito MODULAR"
 * y no "monolito". Sin verificacion automatica, las fronteras se erosionan
 * en una semana.
 */
class ArquitecturaTest {

    private static JavaClasses clases;

    @BeforeAll
    static void importar() {
        clases = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.uagrm.colabmodel");
    }

    @Test
    void modelcore_no_depende_de_ningun_otro_modulo() {
        // Anclado a "com.uagrm.colabmodel." (sin ".." al inicio): un patron
        // "..persistence.." tambien matchea jakarta.persistence por substring
        // y tumbaba el test con un falso positivo sobre las propias entidades
        // JPA de modelcore.
        noClasses()
            .that().resideInAPackage("..modelcore..")
            .should().dependOnClassesThat()
            .resideInAnyPackage(
                "com.uagrm.colabmodel.collab..", "com.uagrm.colabmodel.iam..",
                "com.uagrm.colabmodel.project..", "com.uagrm.colabmodel.ai..",
                "com.uagrm.colabmodel.vision..", "com.uagrm.colabmodel.interop..",
                "com.uagrm.colabmodel.codegen..", "com.uagrm.colabmodel.persistence..",
                "com.uagrm.colabmodel.config..")
            .as("modelcore es el nucleo: no puede depender de nadie (ADR-001)")
            .check(clases);
    }

    @Test
    void no_hay_ciclos_entre_modulos() {
        SlicesRuleDefinition.slices()
            .matching("com.uagrm.colabmodel.(*)..")
            .should().beFreeOfCycles()
            .check(clases);
    }

    // Fase 1: cuando exista la capa de servicios, se activa esta regla.
    // Hoy DiagramRestController toca repositorios directamente a proposito
    // (atajo de Fase 0), asi que el test fallaria y seria ruido, no senal.
    //
    // @Test
    // void los_controladores_no_tocan_repositorios_directamente() { ... }
}
