package com.uagrm.colabmodel;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * ColabModel - Herramienta colaborativa de diseno de datos.
 *
 * Monolito modular (ADR-001). Las fronteras entre modulos se verifican
 * con ArchUnit en ArquitecturaTest: modelcore no depende de nadie.
 */
@SpringBootApplication
public class ColabModelApplication {

    public static void main(String[] args) {
        SpringApplication.run(ColabModelApplication.class, args);
    }
}
