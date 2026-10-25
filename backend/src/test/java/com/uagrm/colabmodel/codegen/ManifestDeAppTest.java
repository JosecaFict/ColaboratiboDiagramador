package com.uagrm.colabmodel.codegen;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uagrm.colabmodel.modelcore.mapping.Enumeraciones;
import com.uagrm.colabmodel.modelcore.mapping.ModeloCanonico;
import com.uagrm.colabmodel.modelcore.metamodel.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * `app-manifest.json` (ADR-021) y su ESPEJO en Dart.
 *
 * Los manifiestos contra los que prueba el motor de intenciones del movil
 * (`mobile/test/manifests/`) no se escriben a mano: son exactamente lo que
 * emite este generador. Si el generador cambia lo que emite, esta prueba
 * falla hasta regenerarlos, y entonces corren las pruebas de Dart contra el
 * archivo nuevo. Asi el contrato no puede cambiar de un solo lado.
 *
 * Regenerar: mvn test -Dtest=ManifestDeAppTest -Dmanifests.actualizar=true
 */
class ManifestDeAppTest {

    private static final Path CARPETA = Path.of("..", "mobile", "test", "manifests");
    private static final boolean ACTUALIZAR = Boolean.getBoolean("manifests.actualizar");
    private final ObjectMapper json = new ObjectMapper();

    // ----------------------------------------------------------- EL ESPEJO

    @ParameterizedTest
    @ValueSource(strings = {"01-uno-a-uno", "02-uno-a-muchos", "03-muchos-a-muchos", "04-herencia",
            "05-clase-asociativa", "06-nombres-acentos", "07-reflexiva-doble"})
    void el_manifiesto_de_cada_canonico_es_el_que_prueba_el_movil(String canonico) throws IOException {
        ModeloCanonico.Cargado m = ModeloCanonico.cargar(canonico + ".json");
        String nombre = json.readTree(Path.of("..", "canonical-models", canonico + ".json").toFile())
                .path("nombre").asText();
        comparar(canonico, manifiesto(GeneradorSpringBoot.generar(GeneradorSpringBoot.Opciones.por(nombre),
                m.clases(), m.atributos(), List.of(), m.relaciones())));
    }

    @Test
    void el_manifiesto_de_la_biblioteca_es_el_que_prueba_el_movil() throws IOException {
        comparar("biblioteca", manifiesto(Biblioteca.generar()));
    }

    private static String manifiesto(ProyectoGenerado p) {
        assertThat(p.archivos()).containsKey(ManifestDeApp.RUTA);
        return p.archivos().get(ManifestDeApp.RUTA);
    }

    private static void comparar(String nombre, String generado) throws IOException {
        Path archivo = CARPETA.resolve(nombre + ".json");
        if (ACTUALIZAR) {
            Files.createDirectories(CARPETA);
            Files.writeString(archivo, generado, StandardCharsets.UTF_8);
        }
        assertThat(archivo).as("Falta %s: correr con -Dmanifests.actualizar=true", archivo).exists();
        // git puede convertir fines de linea al hacer checkout en Windows.
        String guardado = Files.readString(archivo, StandardCharsets.UTF_8).replace("\r\n", "\n");
        assertThat(generado)
                .as("El generador emite otro manifiesto que el que prueba el movil. Si el cambio es"
                        + " intencional: -Dmanifests.actualizar=true y correr flutter test en mobile/")
                .isEqualTo(guardado);
    }

    // ------------------------------------------------------------ EL ESQUEMA

    private JsonNode entidad(ProyectoGenerado p, String nombre) throws IOException {
        for (JsonNode e : json.readTree(manifiesto(p)).path("entities")) {
            if (e.path("name").asText().equals(nombre)) return e;
        }
        throw new AssertionError("No esta la entidad " + nombre);
    }

    private static JsonNode campo(JsonNode entidad, String nombre) {
        for (JsonNode f : entidad.path("fields")) {
            if (f.path("name").asText().equals(nombre)) return f;
        }
        throw new AssertionError("No esta el campo " + nombre);
    }

    @Test
    void cada_campo_dice_todo_lo_que_hace_falta_para_rechazar_un_valor() throws IOException {
        JsonNode libro = entidad(Biblioteca.generar(), "Libro");

        assertThat(libro.path("path").asText()).isEqualTo("/api/libro");
        assertThat(libro.path("actions")).extracting(JsonNode::asText).contains("create", "list");

        JsonNode titulo = campo(libro, "titulo");
        assertThat(titulo.path("label").asText()).isEqualTo("título");
        assertThat(titulo.path("type").asText()).isEqualTo("string");
        assertThat(titulo.path("required").asBoolean()).isTrue();
        assertThat(titulo.path("maxLength").asInt()).isEqualTo(255);

        assertThat(campo(libro, "isbn").path("unique").asBoolean()).isTrue();

        JsonNode anio = campo(libro, "anioPublicacion");
        assertThat(anio.path("type").asText()).isEqualTo("integer");
        assertThat(anio.path("min").asInt()).isEqualTo(1450);
        assertThat(anio.path("max").asInt()).isEqualTo(2100);
        assertThat(anio.path("synonyms")).extracting(JsonNode::asText).contains("año publicación");

        JsonNode estado = campo(libro, "estado");
        assertThat(estado.path("type").asText()).isEqualTo("enum");
        assertThat(estado.path("literals")).extracting(JsonNode::asText)
                .containsExactly("DISPONIBLE", "EN_PRESTAMO", "DE_BAJA");
    }

    @Test
    void el_id_no_se_dicta_y_no_figura_entre_los_campos() throws IOException {
        JsonNode libro = entidad(Biblioteca.generar(), "Libro");
        assertThat(libro.path("fields")).extracting(f -> f.path("name").asText()).doesNotContain("id");
    }

    @Test
    void una_referencia_viaja_como_id_y_se_nombra_por_el_rol_y_por_la_entidad() throws IOException {
        ModeloCanonico.Cargado m = ModeloCanonico.cargar("07-reflexiva-doble.json");
        ProyectoGenerado p = GeneradorSpringBoot.generar(GeneradorSpringBoot.Opciones.por("Vuelos"),
                m.clases(), m.atributos(), List.of(), m.relaciones());
        JsonNode origen = campo(entidad(p, "Vuelo"), "origenId");

        assertThat(origen.path("type").asText()).isEqualTo("reference");
        assertThat(origen.path("target").asText()).isEqualTo("Aeropuerto");
        assertThat(origen.path("required").asBoolean()).isTrue();
        assertThat(origen.path("synonyms")).extracting(JsonNode::asText).contains("origen", "aeropuerto");

        // El lado inverso no esta en el DTO: se lista como relacion, con el campo que la sostiene.
        JsonNode aeropuerto = entidad(p, "Aeropuerto");
        assertThat(aeropuerto.path("relations")).anySatisfy(r -> {
            assertThat(r.path("target").asText()).isEqualTo("Vuelo");
            assertThat(r.path("many").asBoolean()).isTrue();
            assertThat(r.path("inverseOf").asText()).isEqualTo("origenId");
        });
    }

    @Test
    void la_clase_abstracta_no_ofrece_crear_y_la_hija_dice_de_quien_hereda() throws IOException {
        ModeloCanonico.Cargado m = ModeloCanonico.cargar("04-herencia.json");
        ProyectoGenerado p = GeneradorSpringBoot.generar(GeneradorSpringBoot.Opciones.por("Personas"),
                m.clases(), m.atributos(), List.of(), m.relaciones());

        JsonNode persona = entidad(p, "Persona");
        assertThat(persona.path("abstract").asBoolean()).isTrue();
        assertThat(persona.path("actions")).extracting(JsonNode::asText).doesNotContain("create");

        JsonNode empleado = entidad(p, "Empleado");
        assertThat(empleado.path("parent").asText()).isEqualTo("Persona");
        // Lo heredado viaja en el DTO de la hija, asi que tambien en su manifiesto.
        assertThat(empleado.path("fields")).extracting(f -> f.path("name").asText())
                .contains("nombre", "ci", "cargo");
    }

    @Test
    void la_ruta_del_manifiesto_es_la_del_controlador() throws IOException {
        ModeloCanonico.Cargado m = ModeloCanonico.cargar("06-nombres-acentos.json");
        ProyectoGenerado p = GeneradorSpringBoot.generar(GeneradorSpringBoot.Opciones.por("Hostil"),
                m.clases(), m.atributos(), List.of(), m.relaciones());
        JsonNode anio = entidad(p, "AnioAcademico");

        assertThat(anio.path("label").asText()).isEqualTo("Año Académico");
        assertThat(anio.path("synonyms")).extracting(JsonNode::asText).contains("año académico");
        assertThat(p.archivos().get("src/main/java/com/uagrm/generado/web/AnioAcademicoController.java"))
                .contains("@RequestMapping(\"" + anio.path("path").asText() + "\")");
    }

    // ------------------------------------------------------ DOMINIO NUEVO

    /**
     * Un dominio que ningun canonico cubre, con lo que los canonicos no tienen:
     * enumeracion, rango y unico. Es el "dominio que nunca vio" del motor.
     */
    static final class Biblioteca {
        private final UUID diagrama = UUID.randomUUID();
        private final List<UmlClass> clases = new ArrayList<>();
        private final List<UmlAttribute> atributos = new ArrayList<>();
        private final List<UmlRelationship> relaciones = new ArrayList<>();

        static ProyectoGenerado generar() {
            Biblioteca b = new Biblioteca();
            UmlClass estado = b.clase("EstadoLibro");
            estado.setStereotype(Enumeraciones.ESTEREOTIPO);
            b.atributo(estado, "Disponible", "string");
            b.atributo(estado, "En préstamo", "string");
            b.atributo(estado, "De baja", "string");

            UmlClass libro = b.clase("Libro");
            b.atributo(libro, "título", "string").setRequired(true);
            b.atributo(libro, "autor", "string");
            UmlAttribute isbn = b.atributo(libro, "isbn", "string");
            isbn.setRequired(true);
            isbn.setUnique(true);
            UmlAttribute anio = b.atributo(libro, "añoPublicación", "int");
            anio.setMinValue(new BigDecimal("1450"));
            anio.setMaxValue(new BigDecimal("2100"));
            UmlAttribute precio = b.atributo(libro, "precio", "decimal");
            precio.setMinValue(BigDecimal.ZERO);
            b.atributo(libro, "estado", "EstadoLibro").setRequired(true);

            UmlClass socio = b.clase("Socio");
            b.atributo(socio, "nombre", "string").setRequired(true);
            UmlAttribute email = b.atributo(socio, "email", "string");
            email.setUnique(true);
            b.atributo(socio, "activo", "boolean");

            UmlClass prestamo = b.clase("Préstamo");
            b.atributo(prestamo, "fechaPrestamo", "date").setRequired(true);
            b.atributo(prestamo, "fechaDevolucion", "date");
            b.atributo(prestamo, "horaRetiro", "datetime");

            b.relacion(libro, prestamo, "libro");
            b.relacion(socio, prestamo, "socio");

            return GeneradorSpringBoot.generar(GeneradorSpringBoot.Opciones.por("Biblioteca"),
                    b.clases, b.atributos, List.of(), b.relaciones);
        }

        private UmlClass clase(String nombre) {
            UmlClass c = new UmlClass();
            c.setId(UUID.nameUUIDFromBytes(nombre.getBytes(StandardCharsets.UTF_8)));
            c.setDiagramId(diagrama);
            c.setName(nombre);
            clases.add(c);
            return c;
        }

        private UmlAttribute atributo(UmlClass c, String nombre, String tipo) {
            UmlAttribute a = new UmlAttribute();
            a.setId(UUID.randomUUID());
            a.setClassId(c.getId());
            a.setName(nombre);
            a.setDataType(tipo);
            a.setOrdinal(atributos.size());
            atributos.add(a);
            return a;
        }

        /** 1 -> 0..*, la FK del lado "muchos" sale del rol. */
        private void relacion(UmlClass uno, UmlClass muchos, String rol) {
            UmlRelationship r = new UmlRelationship();
            r.setId(UUID.randomUUID());
            r.setDiagramId(diagrama);
            r.setSourceClassId(uno.getId());
            r.setTargetClassId(muchos.getId());
            r.setKind(RelationKind.ASSOCIATION);
            r.setSourceRole(rol);
            r.setSourceMultiplicity("1");
            r.setTargetMultiplicity("0..*");
            relaciones.add(r);
        }
    }
}
