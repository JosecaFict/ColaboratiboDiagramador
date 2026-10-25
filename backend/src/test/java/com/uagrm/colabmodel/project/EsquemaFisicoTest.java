package com.uagrm.colabmodel.project;

import com.uagrm.colabmodel.modelcore.mapping.Decisiones;
import com.uagrm.colabmodel.modelcore.mapping.DerivadorLogico;
import com.uagrm.colabmodel.modelcore.mapping.ModeloCanonico;
import com.uagrm.colabmodel.modelcore.mapping.ModeloLogico;
import com.uagrm.colabmodel.modelcore.metamodel.UmlAttribute;
import com.uagrm.colabmodel.modelcore.metamodel.UmlClass;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * La Vista Fisica no puede describir un esquema distinto del que se genera.
 *
 * El detalle por tabla existe para poder NAVEGAR --con 20 tablas, un script de
 * varios cientos de lineas no se lee de corrido-- pero se deriva del mismo
 * ModeloLogico que el script, no parseandolo. Estos tests son la red que
 * sostiene esa promesa: si alguna vez el detalle y el DDL dejan de coincidir,
 * la pantalla estaria mintiendo sobre lo que se entrega.
 */
class EsquemaFisicoTest {

    private static final String[] CANONICOS = {
            "01-uno-a-uno.json", "02-uno-a-muchos.json", "03-muchos-a-muchos.json",
            "04-herencia.json", "05-clase-asociativa.json", "06-nombres-acentos.json",
            "07-reflexiva-doble.json"};

    private EsquemaFisico esquema(String modelo) {
        ModeloCanonico.Cargado m = ModeloCanonico.cargar(modelo);
        ModeloLogico logico = DerivadorLogico.derivar(
                m.clases(), m.atributos(), m.relaciones(), Decisiones.ninguna());
        return DiagramSnapshotService.aEsquemaFisico(logico);
    }

    @ParameterizedTest(name = "modelo {0}")
    @ValueSource(strings = {"01-uno-a-uno.json", "02-uno-a-muchos.json",
            "03-muchos-a-muchos.json", "04-herencia.json", "05-clase-asociativa.json",
            "06-nombres-acentos.json", "07-reflexiva-doble.json"})
    @DisplayName("Cada tabla del detalle esta en el script, y al reves")
    void el_detalle_y_el_script_dicen_lo_mismo(String modelo) {
        EsquemaFisico e = esquema(modelo);

        assertThat(e.tablas()).isNotEmpty();
        for (EsquemaFisico.Tabla t : e.tablas()) {
            assertThat(e.script()).contains("CREATE TABLE " + t.nombre() + " (");
            for (EsquemaFisico.Columna c : t.columnas()) {
                assertThat(e.script())
                        .as("la columna %s.%s tiene que estar en el DDL", t.nombre(), c.nombre())
                        .contains("    " + c.nombre() + " " + c.tipo());
            }
        }
        // Y ninguna tabla del script falta en el detalle.
        long enElScript = e.script().lines().filter(l -> l.startsWith("CREATE TABLE ")).count();
        assertThat(e.tablas()).hasSize((int) enElScript);
    }

    @ParameterizedTest(name = "modelo {0}")
    @ValueSource(strings = {"01-uno-a-uno.json", "02-uno-a-muchos.json",
            "03-muchos-a-muchos.json", "04-herencia.json", "05-clase-asociativa.json",
            "06-nombres-acentos.json", "07-reflexiva-doble.json"})
    @DisplayName("Los nombres de FK e indices son los mismos que emite el DDL")
    void los_nombres_coinciden(String modelo) {
        EsquemaFisico e = esquema(modelo);

        for (EsquemaFisico.Tabla t : e.tablas()) {
            for (EsquemaFisico.Columna c : t.columnas()) {
                if (c.restriccionFk() == null) continue;
                assertThat(e.script()).contains("ADD CONSTRAINT " + c.restriccionFk());
            }
            for (EsquemaFisico.Indice i : t.indices()) {
                assertThat(e.script()).contains("CREATE INDEX " + i.nombre());
            }
        }
        // Tantos indices en el detalle como CREATE INDEX en el script: si la
        // regla de "PostgreSQL no indexa las FK solo" se duplicara, aca se ve.
        long enElScript = e.script().lines().filter(l -> l.startsWith("CREATE INDEX ")).count();
        assertThat(e.resumen().indices()).isEqualTo((int) enElScript);
    }

    @Test
    @DisplayName("`referidaPor` es el inverso de las claves foraneas")
    void navegacion_en_los_dos_sentidos() {
        // Es lo que permite preguntar "quien apunta a cliente" sin leer el
        // script entero, que con 20 tablas es la pregunta normal.
        EsquemaFisico e = esquema("02-uno-a-muchos.json");

        EsquemaFisico.Tabla pedido = buscar(e, "pedido");
        EsquemaFisico.Columna fk = pedido.columnas().stream()
                .filter(c -> c.referencia() != null).findFirst().orElseThrow();

        assertThat(fk.referencia()).isEqualTo("cliente");
        assertThat(buscar(e, "cliente").referidaPor()).contains("pedido");
    }

    @Test
    @DisplayName("El ON DELETE viaja: es donde se ve composicion vs agregacion")
    void on_delete() {
        EsquemaFisico e = esquema("02-uno-a-muchos.json");

        EsquemaFisico.Columna fk = buscar(e, "pedido").columnas().stream()
                .filter(c -> c.referencia() != null).findFirst().orElseThrow();

        assertThat(fk.onDelete()).isNotNull();
        assertThat(e.script()).contains("ON DELETE " + fk.onDelete());
    }

    @Test
    @DisplayName("La clave primaria del detalle es la del DDL")
    void clave_primaria() {
        EsquemaFisico e = esquema("05-clase-asociativa.json");

        for (EsquemaFisico.Tabla t : e.tablas()) {
            if (t.clavePrimaria().isEmpty()) continue;
            assertThat(e.script())
                    .contains("PRIMARY KEY (" + String.join(", ", t.clavePrimaria()) + ")");
        }
    }

    @Test
    @DisplayName("Los contadores cuadran con el detalle")
    void resumen() {
        EsquemaFisico e = esquema("03-muchos-a-muchos.json");

        int columnas = e.tablas().stream().mapToInt(t -> t.columnas().size()).sum();
        long fks = e.tablas().stream()
                .flatMap(t -> t.columnas().stream())
                .filter(c -> c.referencia() != null).count();

        assertThat(e.resumen().tablas()).isEqualTo(e.tablas().size());
        assertThat(e.resumen().columnas()).isEqualTo(columnas);
        assertThat(e.resumen().clavesForaneas()).isEqualTo((int) fks);
        assertThat(e.dialecto()).isEqualTo("PostgreSQL");
    }

    @Test
    @DisplayName("Un modelo con enumeracion trae su CHECK escrito")
    void check_de_enumeracion() {
        for (String modelo : CANONICOS) {
            EsquemaFisico e = esquema(modelo);
            for (EsquemaFisico.Tabla t : e.tablas()) {
                for (EsquemaFisico.Columna c : t.columnas()) {
                    if (c.check() == null) continue;
                    // El CHECK se muestra tal como va al DDL, no reescrito.
                    assertThat(e.script()).contains("CHECK (" + c.check() + ")");
                }
            }
        }
    }

    @Test
    @DisplayName("Cada columna dice de que clase, atributo y relacion sale (ADR-032)")
    void trazabilidad() {
        // La Vista Fisica edita A TRAVES del modelo: cambiar una columna es un
        // comando sobre el atributo que la produjo. Si estos ids faltan, la
        // pantalla tendria que adivinarlos por el nombre de la columna.
        ModeloCanonico.Cargado m = ModeloCanonico.cargar("02-uno-a-muchos.json");
        EsquemaFisico e = DiagramSnapshotService.aEsquemaFisico(DerivadorLogico.derivar(
                m.clases(), m.atributos(), m.relaciones(), Decisiones.ninguna()));
        UmlClass pedido = m.clases().stream().filter(c -> c.getName().equals("Pedido")).findFirst().orElseThrow();
        UmlAttribute fecha = m.atributos().stream()
                .filter(a -> a.getClassId().equals(pedido.getId()) && a.getName().equals("fecha"))
                .findFirst().orElseThrow();

        EsquemaFisico.Tabla t = buscar(e, "pedido");
        assertThat(t.claseId()).isEqualTo(pedido.getId());
        assertThat(columna(t, "fecha").atributoId()).isEqualTo(fecha.getId());
        assertThat(columna(t, "id").atributoId()).as("el id surrogado no sale de ningun atributo").isNull();

        EsquemaFisico.Columna fk = columna(t, "cliente_id");
        assertThat(fk.relacionId()).isEqualTo(m.relaciones().get(0).getId());
        assertThat(fk.atributoId()).isNull();
        // La FK vive en pedido y apunta a Cliente, el ORIGEN de la relacion: la
        // multiplicidad de ese extremo ("1") la hace NOT NULL y su rol
        // ("cliente") le da el nombre. La pantalla edita ESE extremo.
        assertThat(fk.extremo()).isEqualTo("SOURCE");
        assertThat(fk.nullable()).isFalse();
    }

    @Test
    @DisplayName("Una tabla puente no tiene una clase propia que editar")
    void tabla_puente_sin_clase() {
        EsquemaFisico e = esquema("03-muchos-a-muchos.json");

        assertThat(e.tablas()).filteredOn(t -> t.claseId() == null)
                .isNotEmpty()
                .allSatisfy(t -> assertThat(t.origen()).contains("N:M"));
        assertThat(buscar(e, "estudiante").claseId()).isNotNull();
    }

    @ParameterizedTest(name = "modelo {0}")
    @ValueSource(strings = {"01-uno-a-uno.json", "04-herencia.json", "06-nombres-acentos.json"})
    @DisplayName("La huella es la del script, y el mismo modelo da la misma huella")
    void huella(String modelo) {
        // Es lo que se confirma y lo que el generador compara antes de entregar
        // el zip: si dependiera de algo mas que el script, confirmar no diria nada.
        EsquemaFisico a = esquema(modelo);
        EsquemaFisico b = esquema(modelo);

        assertThat(a.hash()).hasSize(64)
                .isEqualTo(EsquemaFisico.huellaDe(a.script()))
                .isEqualTo(b.hash());
        assertThat(a.confirmacion()).as("la derivacion pura no sabe de confirmaciones").isNull();
    }

    private static EsquemaFisico.Columna columna(EsquemaFisico.Tabla t, String nombre) {
        return t.columnas().stream().filter(c -> c.nombre().equals(nombre)).findFirst()
                .orElseThrow(() -> new AssertionError("No hay columna " + nombre + " en " + t.nombre()));
    }

    private static EsquemaFisico.Tabla buscar(EsquemaFisico e, String nombre) {
        return e.tablas().stream().filter(t -> t.nombre().equals(nombre)).findFirst()
                .orElseThrow(() -> new AssertionError("No hay tabla " + nombre + " en el esquema"));
    }
}
