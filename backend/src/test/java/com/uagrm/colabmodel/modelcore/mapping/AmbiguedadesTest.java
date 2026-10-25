package com.uagrm.colabmodel.modelcore.mapping;

import com.uagrm.colabmodel.codegen.GeneradorSpringBoot;
import com.uagrm.colabmodel.codegen.ProyectoGenerado;
import com.uagrm.colabmodel.modelcore.mapping.Ambiguedad.Lectura;
import com.uagrm.colabmodel.modelcore.mapping.Ambiguedad.TipoAmbiguedad;
import com.uagrm.colabmodel.modelcore.metamodel.*;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Los 4 puntos de ambiguedad OO <-> Datos.
 *
 * Las dos lecturas de cada punto son CORRECTAS: lo que no existe es una
 * respuesta universal. Lo que se verifica aca es que la herramienta pregunte
 * en vez de elegir sola, y que la respuesta cambie de verdad el esquema --una
 * pregunta cuya respuesta no hace nada es peor que no preguntar.
 */
class AmbiguedadesTest {

    private final UUID diagrama = UUID.randomUUID();
    private final List<UmlClass> clases = new ArrayList<>();
    private final List<UmlAttribute> atributos = new ArrayList<>();
    private final List<UmlRelationship> relaciones = new ArrayList<>();

    private UmlClass clase(String nombre) {
        UmlClass c = new UmlClass();
        c.setId(UUID.randomUUID());
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

    private UmlRelationship rel(UmlClass origen, UmlClass destino, RelationKind kind,
                                String multOrigen, String multDestino) {
        UmlRelationship r = new UmlRelationship();
        r.setId(UUID.randomUUID());
        r.setDiagramId(diagrama);
        r.setSourceClassId(origen.getId());
        r.setTargetClassId(destino.getId());
        r.setKind(kind);
        r.setSourceMultiplicity(multOrigen);
        r.setTargetMultiplicity(multDestino);
        relaciones.add(r);
        return r;
    }

    private List<Ambiguedad> detectar() {
        return Ambiguedades.detectar(clases, atributos, relaciones);
    }

    private ModeloLogico derivar(Decisiones d) {
        return DerivadorLogico.derivar(clases, atributos, relaciones, d);
    }

    private ModeloLogico.Tabla tabla(ModeloLogico m, String nombre) {
        return m.tablas().stream().filter(t -> t.nombre().equals(nombre)).findFirst().orElse(null);
    }

    private static List<String> pks(ModeloLogico.Tabla t) {
        return t.columnas().stream().filter(ModeloLogico.Columna::pk)
                .map(ModeloLogico.Columna::nombre).toList();
    }

    // ------------------------------------------------------------- DETECCION

    @Test
    void detecta_las_cuatro_construcciones_ambiguas() {
        UmlClass factura = clase("Factura");
        UmlClass detalle = clase("DetalleFactura");
        rel(factura, detalle, RelationKind.COMPOSITION, "1", "0..*");        // A-1

        UmlClass cliente = clase("Cliente");
        UmlClass direccion = clase("Direccion");
        rel(cliente, direccion, RelationKind.COMPOSITION, "1", "1");          // A-2

        atributo(cliente, "telefonos", "string").setMultivalued(true);        // A-3

        UmlClass estudiante = clase("Estudiante");
        UmlClass curso = clase("Curso");
        rel(estudiante, curso, RelationKind.ASSOCIATION, "0..*", "0..*");     // A-4

        assertThat(detectar()).extracting(Ambiguedad::tipo)
                .containsExactlyInAnyOrder(TipoAmbiguedad.COMPOSITION, TipoAmbiguedad.EMBEDDABLE,
                        TipoAmbiguedad.MULTIVALUED, TipoAmbiguedad.ASSOC_CLASS);
    }

    @Test
    void un_modelo_sin_construcciones_ambiguas_no_pregunta_nada() {
        UmlClass a = clase("Cliente");
        UmlClass b = clase("Pedido");
        rel(a, b, RelationKind.ASSOCIATION, "1", "0..*");

        // Una 1:N comun no tiene dos lecturas: la FK va del lado muchos y listo.
        assertThat(detectar()).isEmpty();
    }

    @Test
    void cada_valor_por_defecto_es_lo_que_la_herramienta_ya_hacia() {
        UmlClass factura = clase("Factura");
        UmlClass detalle = clase("DetalleFactura");
        rel(factura, detalle, RelationKind.COMPOSITION, "1", "0..*");
        atributo(factura, "notas", "string").setMultivalued(true);

        // Si los valores por defecto no coincidieran con el comportamiento
        // historico, agregar esta pantalla cambiaria esquemas ya entregados.
        assertThat(detectar()).extracting(Ambiguedad::tipo, Ambiguedad::porDefecto)
                .contains(org.assertj.core.groups.Tuple.tuple(TipoAmbiguedad.COMPOSITION, Lectura.OO),
                          org.assertj.core.groups.Tuple.tuple(TipoAmbiguedad.MULTIVALUED, Lectura.DATA));
    }

    // --------------------------------------------------- A-4: clase asociativa

    @Test
    void la_nm_por_defecto_es_una_tabla_puente_con_clave_compuesta() {
        UmlClass e = clase("Estudiante");
        UmlClass c = clase("Curso");
        rel(e, c, RelationKind.ASSOCIATION, "0..*", "0..*");

        ModeloLogico.Tabla puente = tabla(derivar(Decisiones.ninguna()), "estudiante_curso");

        assertThat(pks(puente)).containsExactly("estudiante_id", "curso_id");
    }

    @Test
    void leida_como_clase_asociativa_la_nm_recibe_id_surrogado_y_genera_entity() {
        UmlClass e = clase("Estudiante");
        UmlClass c = clase("Curso");
        UmlRelationship r = rel(e, c, RelationKind.ASSOCIATION, "0..*", "0..*");

        Decisiones d = Decisiones.constructor()
                .con(TipoAmbiguedad.ASSOC_CLASS, r.getId(), Lectura.OO).construir();
        ModeloLogico.Tabla puente = tabla(derivar(d), "estudiante_curso");

        // La sola PK "id" es lo que le permite al generador emitirle las cinco
        // capas: era el hueco mas visible del generador.
        assertThat(pks(puente)).containsExactly("id");
        ProyectoGenerado p = GeneradorSpringBoot.generar(
                GeneradorSpringBoot.Opciones.por("Demo"), clases, atributos, List.of(), relaciones, d);
        assertThat(p.archivos())
                .containsKey("src/main/java/com/uagrm/generado/domain/EstudianteCurso.java");
    }

    @Test
    void la_clase_asociativa_avisa_que_ya_no_impide_el_par_repetido() {
        UmlClass e = clase("Estudiante");
        UmlClass c = clase("Curso");
        UmlRelationship r = rel(e, c, RelationKind.ASSOCIATION, "0..*", "0..*");

        ModeloLogico m = derivar(Decisiones.constructor()
                .con(TipoAmbiguedad.ASSOC_CLASS, r.getId(), Lectura.OO).construir());

        // Con PK compuesta el par unico salia gratis; con id surrogado, no.
        assertThat(m.advertencias()).anyMatch(a -> a.contains("UNIQUE"));
    }

    // ------------------------------------------------------- A-3: multivaluado

    @Test
    void leido_como_element_collection_la_tabla_es_la_misma_pero_no_pide_entity() {
        UmlClass libro = clase("Libro");
        UmlAttribute autor = atributo(libro, "autor", "string");
        autor.setMultivalued(true);

        Decisiones d = Decisiones.constructor()
                .con(TipoAmbiguedad.MULTIVALUED, autor.getId(), Lectura.OO).construir();

        // 1FN no se negocia: la tabla existe en las dos lecturas.
        assertThat(tabla(derivar(d), "libro_autor")).isNotNull();

        ProyectoGenerado p = GeneradorSpringBoot.generar(
                GeneradorSpringBoot.Opciones.por("Demo"), clases, atributos, List.of(), relaciones, d);
        assertThat(p.archivos().get("src/main/java/com/uagrm/generado/domain/Libro.java"))
                .contains("@ElementCollection")
                .contains("@CollectionTable(name = \"libro_autor\", joinColumns = @JoinColumn(name = \"libro_id\"))")
                .contains("private List<String> autor = new ArrayList<>();");
        assertThat(p.advertencias()).noneMatch(a -> a.contains("libro_autor") && a.contains("Entity"));
    }

    @Test
    void sin_decidir_el_multivaluado_sigue_siendo_una_tabla_sin_entity() {
        UmlClass libro = clase("Libro");
        atributo(libro, "autor", "string").setMultivalued(true);

        ProyectoGenerado p = GeneradorSpringBoot.generar(
                GeneradorSpringBoot.Opciones.por("Demo"), clases, atributos, List.of(), relaciones);

        assertThat(p.archivos().get("src/main/java/com/uagrm/generado/domain/Libro.java"))
                .doesNotContain("@ElementCollection");
        assertThat(p.advertencias()).anyMatch(a -> a.contains("libro_autor"));
    }

    // ---------------------------------------------------------- A-2: embeddable

    @Test
    void leida_como_embeddable_la_parte_se_aplana_y_pierde_su_tabla() {
        UmlClass cliente = clase("Cliente");
        UmlClass direccion = clase("Direccion");
        atributo(cliente, "nombre", "string");
        atributo(direccion, "calle", "string");
        atributo(direccion, "ciudad", "string");
        UmlRelationship r = rel(cliente, direccion, RelationKind.COMPOSITION, "1", "1");

        ModeloLogico m = derivar(Decisiones.constructor()
                .con(TipoAmbiguedad.EMBEDDABLE, r.getId(), Lectura.OO).construir());

        assertThat(tabla(m, "direccion")).isNull();
        assertThat(tabla(m, "cliente").columnas()).extracting(ModeloLogico.Columna::nombre)
                .containsExactly("id", "nombre", "direccion_calle", "direccion_ciudad");
    }

    @Test
    void no_se_aplana_una_parte_que_otros_referencian() {
        UmlClass cliente = clase("Cliente");
        UmlClass direccion = clase("Direccion");
        UmlClass envio = clase("Envio");
        atributo(direccion, "calle", "string");
        UmlRelationship r = rel(cliente, direccion, RelationKind.COMPOSITION, "1", "1");
        rel(envio, direccion, RelationKind.ASSOCIATION, "0..*", "1");

        ModeloLogico m = derivar(Decisiones.constructor()
                .con(TipoAmbiguedad.EMBEDDABLE, r.getId(), Lectura.OO).construir());

        // Aplanarla dejaria la FK de Envio apuntando a una tabla inexistente.
        assertThat(tabla(m, "direccion")).isNotNull();
        assertThat(m.advertencias()).anyMatch(a -> a.contains("otras relaciones"));
    }

    // ------------------------------------------------------- A-1: composicion

    @Test
    void leida_como_entidad_debil_la_parte_comparte_la_clave_del_todo() {
        UmlClass factura = clase("Factura");
        UmlClass detalle = clase("DetalleFactura");
        atributo(detalle, "linea", "int").setPartialKey(true);
        UmlRelationship r = rel(factura, detalle, RelationKind.COMPOSITION, "1", "0..*");

        ModeloLogico.Tabla t = tabla(derivar(Decisiones.constructor()
                .con(TipoAmbiguedad.COMPOSITION, r.getId(), Lectura.DATA).construir()),
                "detalle_factura");

        assertThat(pks(t)).containsExactly("factura_id", "linea");
        // La FK no puede salir dos veces: ya es parte de la clave.
        assertThat(t.columnas()).extracting(ModeloLogico.Columna::nombre)
                .containsExactly("factura_id", "linea");
    }

    @Test
    void la_entidad_debil_sin_clave_parcial_avisa_que_no_distingue_dos_filas() {
        UmlClass factura = clase("Factura");
        UmlClass detalle = clase("DetalleFactura");
        atributo(detalle, "monto", "decimal");
        UmlRelationship r = rel(factura, detalle, RelationKind.COMPOSITION, "1", "0..*");

        ModeloLogico m = derivar(Decisiones.constructor()
                .con(TipoAmbiguedad.COMPOSITION, r.getId(), Lectura.DATA).construir());

        assertThat(m.advertencias()).anyMatch(a -> a.contains("clave parcial"));
    }

    @Test
    void por_defecto_la_composicion_sigue_dando_una_entidad_con_id_propio() {
        UmlClass factura = clase("Factura");
        UmlClass detalle = clase("DetalleFactura");
        atributo(detalle, "monto", "decimal");
        rel(factura, detalle, RelationKind.COMPOSITION, "1", "0..*");

        ModeloLogico.Tabla t = tabla(derivar(Decisiones.ninguna()), "detalle_factura");

        assertThat(pks(t)).containsExactly("id");
        assertThat(t.columnas()).extracting(ModeloLogico.Columna::nombre)
                .contains("factura_id");
    }

    @Test
    void la_clase_asociativa_generada_queda_lista_para_compilar() throws java.io.IOException {
        UmlClass e = clase("Estudiante");
        UmlClass c = clase("Curso");
        atributo(e, "carnet", "string");
        atributo(c, "sigla", "string");
        UmlRelationship r = rel(e, c, RelationKind.ASSOCIATION, "0..*", "0..*");

        ProyectoGenerado p = GeneradorSpringBoot.generar(
                GeneradorSpringBoot.Opciones.por("Asociativa"), clases, atributos, List.of(), relaciones,
                Decisiones.constructor().con(TipoAmbiguedad.ASSOC_CLASS, r.getId(), Lectura.OO)
                        .construir());

        // Los dos extremos dejan de ser columnas sueltas y pasan a ser
        // asociaciones navegables, igual que en cualquier otra entidad.
        String entidad = p.archivos().get("src/main/java/com/uagrm/generado/domain/EstudianteCurso.java");
        assertThat(entidad)
                .contains("@ManyToOne(fetch = FetchType.LAZY)")
                .contains("private Estudiante estudiante;")
                .contains("private Curso curso;");
        // Y el lado inverso aparece en las dos clases originales.
        assertThat(p.archivos().get("src/main/java/com/uagrm/generado/domain/Estudiante.java"))
                .contains("private List<EstudianteCurso> estudianteCursos = new ArrayList<>();");

        // Se deja en target/ para poder correrle `mvn package` encima: que el
        // archivo exista no prueba que compile.
        p.escribirEn(java.nio.file.Path.of("target", "generado-asociativa"));
    }
}
