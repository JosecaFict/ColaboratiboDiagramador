package com.uagrm.colabmodel.interop;

import com.uagrm.colabmodel.modelcore.mapping.InferenciaDeAtributos;
import com.uagrm.colabmodel.modelcore.metamodel.UmlAttribute;
import com.uagrm.colabmodel.modelcore.metamodel.UmlAttributeRepository;
import com.uagrm.colabmodel.modelcore.metamodel.UmlClass;
import com.uagrm.colabmodel.modelcore.metamodel.UmlClassRepository;
import com.uagrm.colabmodel.modelcore.metamodel.UmlMethodRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Importar: leer un archivo y proponer, sin escribir nada.
 *
 * Se leen los modelos canonicos REALES, no copias: si alguien agrega un caso
 * dificil a canonical-models/, la importacion lo ejercita sola.
 */
class ImportacionTest {

    private final UUID diagramId = UUID.randomUUID();
    private final LectorModeloJson lector = new LectorModeloJson();

    private static byte[] canonico(String archivo) throws IOException {
        Path ruta = Path.of("..", "canonical-models", archivo);
        if (!Files.exists(ruta)) ruta = Path.of("canonical-models", archivo);
        return Files.readAllBytes(ruta);
    }

    private ServicioDeImportacion servicio(List<UmlClass> existentes, List<UmlAttribute> atributosExistentes) {
        UmlClassRepository classes = mock(UmlClassRepository.class);
        when(classes.findByDiagramIdAndDeletedAtIsNullOrderByNameAsc(diagramId)).thenReturn(existentes);
        UmlAttributeRepository attributes = mock(UmlAttributeRepository.class);
        when(attributes.findByClassIdInOrderByOrdinalAsc(anyCollection())).thenReturn(atributosExistentes);
        return new ServicioDeImportacion(List.of(lector), classes, attributes, mock(UmlMethodRepository.class));
    }

    // ------------------------------------------------------------------ LECTOR

    @ParameterizedTest
    @ValueSource(strings = {"01-uno-a-uno.json", "02-uno-a-muchos.json", "03-muchos-a-muchos.json",
            "04-herencia.json", "05-clase-asociativa.json", "06-nombres-acentos.json", "07-reflexiva-doble.json"})
    void lee_los_siete_modelos_canonicos(String archivo) throws IOException {
        ModeloLeido m = lector.leer(canonico(archivo));

        assertThat(m.clases()).isNotEmpty();
        assertThat(m.advertencias()).noneMatch(a -> a.contains("se omitio"));
    }

    @Test
    void conserva_clases_atributos_y_relaciones_del_archivo() throws IOException {
        ModeloLeido m = lector.leer(canonico("02-uno-a-muchos.json"));

        assertThat(m.clases()).extracting(ModeloLeido.ClaseLeida::nombre).containsExactly("Cliente", "Pedido");
        assertThat(m.clases().get(1).atributos()).extracting(ModeloLeido.AtributoLeido::tipoLeido)
                .containsExactly("datetime", "decimal");
        assertThat(m.relaciones()).singleElement().satisfies(r -> {
            assertThat(r.multiplicidadOrigen()).isEqualTo("1");
            assertThat(r.multiplicidadDestino()).isEqualTo("0..*");
        });
    }

    @Test
    void un_archivo_que_no_es_json_dice_donde_falla() {
        var e = catchThrowableOfType(() -> lector.leer("{ \"clases\": [ ".getBytes(StandardCharsets.UTF_8)),
                ArchivoInvalidoException.class);
        assertThat(e.getMessage()).contains("no es un JSON valido").contains("linea")
                .contains("antes de cerrar").doesNotContain("Source");
    }

    @Test
    void un_json_que_no_es_un_modelo_se_rechaza_con_una_pista() {
        var e = catchThrowableOfType(() -> lector.leer("{\"hola\": 1}".getBytes(StandardCharsets.UTF_8)),
                ArchivoInvalidoException.class);
        assertThat(e.getMessage()).contains("\"clases\"");
    }

    @Test
    void una_relacion_hacia_una_clase_ausente_se_omite_y_se_avisa() {
        String json = """
                { "clases": [ { "id": "a", "name": "Libro" } ],
                  "relaciones": [ { "sourceClassId": "a", "targetClassId": "zz", "kind": "ASSOCIATION" } ] }
                """;
        ModeloLeido m = lector.leer(json.getBytes(StandardCharsets.UTF_8));

        assertThat(m.relaciones()).isEmpty();
        assertThat(m.advertencias()).anyMatch(a -> a.contains("no esta en el archivo"));
    }

    // ------------------------------------------------------------ PROPUESTA

    @Test
    void un_formato_sin_lector_dice_cuales_si_se_pueden_importar() {
        var e = catchThrowableOfType(() -> servicio(List.of(), List.of())
                .proponer(diagramId, "modelo.xmi", new byte[] {1}), FormatoNoSoportadoException.class);
        assertThat(e.getMessage()).contains("modelo.xmi").contains("JSON");
    }

    @Test
    void una_clase_que_ya_existe_no_se_crea_de_nuevo_y_se_marca_lo_que_ya_tiene() throws IOException {
        UmlClass cliente = new UmlClass();
        cliente.setId(UUID.randomUUID());
        cliente.setDiagramId(diagramId);
        cliente.setName("cliente");   // otra capitalizacion: el indice unico ignora mayusculas
        UmlAttribute nombre = new UmlAttribute();
        nombre.setClassId(cliente.getId());
        nombre.setName("Nombre");

        PropuestaDeImportacion p = servicio(List.of(cliente), List.of(nombre))
                .proponer(diagramId, "02.json", canonico("02-uno-a-muchos.json"));

        PropuestaDeImportacion.ClasePropuesta c = p.clases().get(0);
        assertThat(c.idExistente()).isEqualTo(cliente.getId());
        assertThat(c.atributos()).singleElement().satisfies(a -> assertThat(a.yaExiste()).isTrue());
        assertThat(p.clases().get(1).idExistente()).isNull();
        assertThat(p.advertencias()).anyMatch(a -> a.contains("ya existe"));
    }

    @Test
    void cada_atributo_trae_su_sugerencia_y_las_fks_se_reconocen_contra_el_mismo_archivo() {
        String json = """
                { "clases": [
                    { "id": "c", "name": "Cliente", "attributes": [ { "name": "email" } ] },
                    { "id": "p", "name": "Pedido",  "attributes": [
                        { "name": "cliente_id", "dataType": "uuid" },
                        { "name": "fechaEntrega" } ] } ] }
                """;
        PropuestaDeImportacion p = servicio(List.of(), List.of())
                .proponer(diagramId, "m.json", json.getBytes(StandardCharsets.UTF_8));

        UUID idCliente = p.clases().get(0).id();
        PropuestaDeImportacion.AtributoPropuesto fk = p.clases().get(1).atributos().get(0);
        // Cliente todavia no existe en el diagrama, pero viene en el archivo.
        assertThat(fk.sugerencia().rol()).isEqualTo(InferenciaDeAtributos.Rol.RELACION);
        assertThat(fk.sugerencia().claseRelacionadaId()).isEqualTo(idCliente);
        assertThat(p.clases().get(1).atributos().get(1).tipo()).isEqualTo("date");
    }

    @Test
    void proponer_no_escribe_nada() throws IOException {
        UmlClassRepository classes = mock(UmlClassRepository.class);
        UmlAttributeRepository attributes = mock(UmlAttributeRepository.class);
        new ServicioDeImportacion(List.of(lector), classes, attributes, mock(UmlMethodRepository.class))
                .proponer(diagramId, "04.json", canonico("04-herencia.json"));

        // La escritura llega recien como BATCH por el bus, si el usuario confirma.
        verify(classes, never()).save(any());
        verify(classes, never()).saveAndFlush(any());
        verify(attributes, never()).save(any());
    }
}
