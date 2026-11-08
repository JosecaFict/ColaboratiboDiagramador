package com.uagrm.colabmodel.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.uagrm.colabmodel.modelcore.command.Origin;
import com.uagrm.colabmodel.modelcore.metamodel.*;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Evaluacion optativa, secuencial y sin escribir proyectos ni diagramas reales. */
@EnabledIfEnvironmentVariable(named = "OLLAMA_CINCO_DOMINIOS", matches = "1")
@org.junit.jupiter.api.parallel.Execution(org.junit.jupiter.api.parallel.ExecutionMode.SAME_THREAD)
class CincoDominiosOllamaVivoTest {
    @TestFactory
    List<DynamicTest> dominios() {
        var requisitos = new LinkedHashMap<String, String>();
        requisitos.put("ventas", "Venta de computadoras y productos tecnologicos: catalogo, inventario actual, clientes, "
                + "ventas con cantidades y precio unitario historico, pagos y garantias de lo vendido. Cada garantia identifica "
                + "el detalle o unidad vendida que cubre. Conserva historial de ventas y pagos.");
        requisitos.put("academico", "Plan academico universitario: carreras, planes versionados con vigencia, catalogo de "
                + "asignaturas, creditos y nivel en cada plan, prerrequisitos, periodos academicos, docentes y asignaciones "
                + "docentes. Conserva el contexto de plan y periodo de cada asignacion. Evita prerrequisitos circulares.");
        requisitos.put("banco", "Sistema bancario: clientes, cuentas con titulares multiples y porcentaje de titularidad, "
                + "historial de movimientos monetarios, transferencias con cuentas origen y destino, prestamos a clientes, "
                + "cuotas y pagos parciales de cuotas. Cada prestamo identifica al cliente prestatario. Movimiento es un cargo "
                + "o abono de una cuenta; Transferencia enlaza dos cuentas. Conserva fechas e importes.");
        requisitos.put("ganaderia", "Ganaderia: establecimientos, propietarios, lotes, animales identificables, especies y "
                + "razas, parentesco con padre y madre, historial de movimientos entre lotes con origen y destino, controles "
                + "sanitarios y aplicaciones de productos con fecha y dosis. Cada animal tiene especie, raza y ubicacion. "
                + "Evita duplicar animales por su rol familiar. Los catalogos pueden existir antes de registrar animales.");
        requisitos.put("turismo", "Agencia de turismo: destinos y servicios turisticos reutilizables, paquetes, salidas con "
                + "fecha y cupos, itinerarios con pasos ordenados vinculados al paquete o salida, reservas de salidas concretas, "
                + "viajeros participantes, pagos y cancelaciones opcionales que conserven historial. Registra el precio acordado "
                + "de cada reserva. Una cancelacion no elimina la reserva ni sus pagos.");
        List<DynamicTest> pruebas = new ArrayList<>();
        Set<String> seleccion = new HashSet<>(Arrays.asList(System.getenv().getOrDefault("OLLAMA_DOMINIOS",
                "ventas,academico,banco,ganaderia,turismo").split(",")));
        requisitos.forEach((nombre, requisito) -> { if (seleccion.contains(nombre))
            pruebas.add(DynamicTest.dynamicTest(nombre, () -> ejecutar(nombre, requisito))); });
        return pruebas;
    }

    private void ejecutar(String nombre, String requisito) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        String modelo = System.getenv().getOrDefault("OLLAMA_MODEL", "qwen3.5:9b-q4_K_M");
        ProveedorDeIA ollama = new ProveedorOllama("http://localhost:11434", 8192, 0.1, 180_000,
                mapper, new CatalogoModelosOllama(modelo));
        ProveedorDeIA proveedor = new ProveedorDeIA() {
            private int turno;
            public String nombre() { return ollama.nombre(); }
            public RespuestaIA generar(SolicitudIA solicitud) {
                long inicio = System.nanoTime();
                System.out.println(nombre + ": turno " + (++turno) + " modo " + solicitud.modo());
                try {
                    RespuestaIA respuesta = ollama.generar(solicitud);
                    try {
                        Path trazas = Path.of("target", "evaluaciones-ia", "trazas");
                        Files.createDirectories(trazas);
                        Files.writeString(trazas.resolve(nombre + "-turno-" + turno + ".json"),
                                mapper.writerWithDefaultPrettyPrinter().writeValueAsString(respuesta));
                    } catch (java.io.IOException e) { throw new IllegalStateException("No se pudo guardar la evaluacion", e); }
                    return respuesta;
                }
                finally { System.out.println(nombre + ": turno finalizado en "
                        + java.time.Duration.ofNanos(System.nanoTime() - inicio).toSeconds() + " s"); }
            }
        };
        UmlClassRepository clases = mock(UmlClassRepository.class);
        when(clases.findByDiagramIdAndDeletedAtIsNullOrderByNameAsc(any())).thenReturn(List.of());
        UmlRelationshipRepository relaciones = mock(UmlRelationshipRepository.class);
        when(relaciones.findByDiagramIdAndDeletedAtIsNull(any())).thenReturn(List.of());
        ServicioDeIA servicio = new ServicioDeIA(proveedor, clases, mock(UmlAttributeRepository.class),
                mock(UmlMethodRepository.class), relaciones);
        Path carpeta = Path.of("target", "evaluaciones-ia"); Files.createDirectories(carpeta);
        Files.deleteIfExists(carpeta.resolve(nombre + ".error.txt"));
        Files.deleteIfExists(carpeta.resolve(nombre + ".json"));
        Path trazas = carpeta.resolve("trazas");
        if (Files.isDirectory(trazas)) try (var archivos = Files.list(trazas)) {
            for (Path archivo : archivos.filter(p -> p.getFileName().toString().matches(nombre + "-turno-[0-9]+\\.json")).toList())
                Files.delete(archivo);
        }
        System.out.println("Evaluando dominio real: " + nombre);
        try {
            PropuestaDeIA propuesta = servicio.generar(UUID.randomUUID(), "Actua como arquitecto UML. Deduce las entidades, "
                    + "atributos y relaciones a partir de estos requisitos, en un diagrama vacio. " + requisito
                    + " Genera una propuesta completa dentro de 90 cambios. Prioriza requisitos esenciales sobre "
                    + "atributos descriptivos; usa roles y clases asociativas sin datos adicionales si corresponde. "
                    + "Usa nombres espanoles ASCII y multiplicidades en ambos extremos de asociaciones.",
                    Origin.TEXT, ModoIA.DISENAR, modelo);
            Files.writeString(carpeta.resolve(nombre + ".json"), mapper.writerWithDefaultPrettyPrinter().writeValueAsString(propuesta));
            assertThat(propuesta.aceptados()).isNotEmpty();
            assertThat(propuesta.rechazados()).isEmpty(); assertThat(propuesta.truncada()).isFalse();
            verify(clases, never()).saveAndFlush(any());
            System.out.println("Resultado " + nombre + ": " + propuesta.aceptados().size() + " cambios; "
                    + propuesta.advertencias().size() + " advertencias");
        } catch (RuntimeException e) {
            Files.writeString(carpeta.resolve(nombre + ".error.txt"), e.toString());
            throw e;
        }
    }
}
