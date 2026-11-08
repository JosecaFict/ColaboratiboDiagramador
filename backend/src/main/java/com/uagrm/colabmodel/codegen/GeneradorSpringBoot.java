package com.uagrm.colabmodel.codegen;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uagrm.colabmodel.modelcore.mapping.*;
import com.uagrm.colabmodel.modelcore.metamodel.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * ESQUELETO DEL GENERADOR (P2).
 *
 * Toma el modelo conceptual y emite un proyecto Spring Boot de 5 capas que
 * COMPILA. El DDL y las entidades salen de la misma derivacion que la Vista
 * Logica (`modelcore/mapping`), asi que lo que el usuario vio en pantalla es
 * exactamente lo que se le entrega.
 *
 * Limites declarados de esta version (van tambien al README generado, porque
 * la Regla 10 dice que lo que no se hace se lista, no se calla):
 *   - Las tablas con PK compuesta (entidad debil, tabla puente) entran en el
 *     DDL pero todavia no generan Entity: harian falta @IdClass/@EmbeddedId.
 *     Por eso tampoco se emite @ManyToMany para las N:M.
 *   - SINGLE_TABLE (Regla 4) solo se aplica si las subclases no tienen
 *     relaciones ni multivaluados propios; si no, la jerarquia queda JOINED.
 */
public final class GeneradorSpringBoot {

    /**
     * Donde guarda los datos el backend generado (ADR-035). PostgreSQL es la
     * salida por defecto; MongoDB emite las mismas rutas, los mismos DTO y el
     * mismo manifiesto, asi que quien consume la API no se entera.
     */
    public enum Almacen {
        POSTGRES, MONGO;

        /** "postgres" o "mongo" (sin importar mayusculas); null o vacio es POSTGRES. */
        public static Almacen de(String texto) {
            if (texto == null || texto.isBlank()) return POSTGRES;
            return switch (texto.trim().toLowerCase(java.util.Locale.ROOT)) {
                case "postgres", "postgresql" -> POSTGRES;
                case "mongo", "mongodb" -> MONGO;
                default -> throw new IllegalArgumentException(
                        "Almacen desconocido: '" + texto + "'. Valores: postgres, mongo");
            };
        }
    }

    public record Opciones(String nombreProyecto, String paquete, Almacen almacen) {
        public Opciones(String nombreProyecto, String paquete) {
            this(nombreProyecto, paquete, Almacen.POSTGRES);
        }

        public static Opciones por(String nombreProyecto) {
            return por(nombreProyecto, Almacen.POSTGRES);
        }

        public static Opciones por(String nombreProyecto, Almacen almacen) {
            return new Opciones(nombreProyecto, "com.uagrm.generado", almacen);
        }
    }

    /**
     * Todo lo que el generador sabe de UNA entidad antes de escribirla. Se
     * junta primero y se escribe despues, porque la misma entidad sale como
     * JPA o como documento de MongoDB y las dos tienen que salir del mismo
     * analisis: si cada variante decidiera por su cuenta, sus DTO podrian
     * dejar de ser iguales.
     *
     * @param tabla       la tabla de la clase (da la ruta REST)
     * @param tablaFisica donde viven sus filas: en SINGLE_TABLE, la de la raiz
     * @param propios     lo que la clase declara (la Entity / el documento)
     * @param campos      lo que la clase ES, con lo heredado (el DTO y el Service)
     */
    record Unidad(String clase, String nombreUml, String tabla, String tablaFisica,
                  List<Campo> propios, List<Asociacion> asociacionesPropias, List<Coleccion> colecciones,
                  List<ColeccionDeValores> valoresPropios, Herencia herencia,
                  List<ModeloLogico.Incrustado> incrustadosPropios,
                  List<Campo> campos, List<Asociacion> asociaciones, List<ColeccionDeValores> valores,
                  List<ModeloLogico.Incrustado> incrustados, Stubs stubs) {

        boolean instanciable() { return !herencia.abstracta(); }

        String recurso() { return recursoDe(tabla); }
    }

    /**
     * Los campos de auditoria y de bloqueo optimista (ADR-034). Si el modelo
     * ya usa alguno de esos nombres --un Software con `version`--, se cambian
     * los tres para todo el proyecto en vez de emitir dos campos homonimos.
     */
    record Meta(String version, String creado, String actualizado,
                String columnaVersion, String columnaCreado, String columnaActualizado) {

        static Meta para(List<Unidad> unidades) {
            Set<String> usados = new HashSet<>();
            for (Unidad u : unidades) {
                u.campos().forEach(c -> usados.add(c.nombreJava()));
                u.asociaciones().forEach(a -> usados.add(a.campo() + "Id"));
                u.valores().forEach(v -> usados.add(v.campo()));
                u.incrustados().forEach(i -> usados.add(NombresJava.campo(i.campo())));
            }
            boolean choca = usados.contains("version") || usados.contains("createdAt")
                    || usados.contains("updatedAt");
            return choca
                    ? new Meta("versionRegistro", "registroCreadoEn", "registroActualizadoEn",
                            "version_registro", "registro_creado_en", "registro_actualizado_en")
                    : new Meta("version", "createdAt", "updatedAt", "version", "created_at", "updated_at");
        }
    }

    /**
     * Donde va el DDL dentro del proyecto. Publico porque quien entrega el zip
     * compara ESTE archivo con la base confirmada (ADR-032).
     */
    public static final String RUTA_DDL = "src/main/resources/db/migration/V1__init.sql";

    private GeneradorSpringBoot() { }

    public static ProyectoGenerado generar(Opciones opciones,
                                           List<UmlClass> clases,
                                           List<UmlAttribute> atributos,
                                           List<UmlMethod> metodos,
                                           List<UmlRelationship> relaciones) {
        return generar(opciones, clases, atributos, metodos, relaciones, Decisiones.ninguna());
    }

    public static ProyectoGenerado generar(Opciones opciones,
                                           List<UmlClass> clases,
                                           List<UmlAttribute> atributos,
                                           List<UmlMethod> metodos,
                                           List<UmlRelationship> relaciones,
                                           Decisiones decisiones) {
        return generarCompleto(opciones, clases, atributos, metodos, relaciones, decisiones).proyecto();
    }

    /** El zip y la Vista Documental, salidos del MISMO analisis. */
    private record Resultado(ProyectoGenerado proyecto, MapeoDocumental documental) { }

    private static Resultado generarCompleto(Opciones opciones,
                                             List<UmlClass> clases,
                                             List<UmlAttribute> atributos,
                                             List<UmlMethod> metodos,
                                             List<UmlRelationship> relaciones,
                                             Decisiones decisiones) {
        ModeloLogico logico = DerivadorLogico.derivar(clases, atributos, relaciones, decisiones);
        Map<String, String> archivos = new LinkedHashMap<>();
        List<String> advertencias = new ArrayList<>(logico.advertencias());

        String pkg = opciones.paquete();
        String ruta = "src/main/java/" + pkg.replace('.', '/');
        boolean mongo = opciones.almacen() == Almacen.MONGO;
        List<Unidad> unidades = new ArrayList<>();
        Map<String, List<Campo>> camposDeValores = new LinkedHashMap<>();

        List<UmlClass> vivas = clases.stream().filter(c -> c.getDeletedAt() == null).toList();
        Map<UUID, UmlRelationship> porRelacion = relaciones.stream()
                .filter(r -> r.getDeletedAt() == null)
                .collect(Collectors.toMap(UmlRelationship::getId, r -> r, (a, b) -> a));
        Map<String, String> claseDeTabla = new LinkedHashMap<>(vivas.stream().collect(Collectors.toMap(
                c -> Nombres.tabla(c.getName()), c -> NombresJava.clase(c.getName()), (a, b) -> a)));

        // A-4 leido como OO: la tabla puente dejo de tener clave compuesta y
        // paso a tener un `id` propio, asi que ya no es una tabla de apoyo:
        // es una entidad y le corresponden sus cinco capas. Se registran ANTES
        // de calcular asociaciones para que el lado inverso las encuentre.
        Map<String, ModeloLogico.Tabla> asociativas = new LinkedHashMap<>();
        for (ModeloLogico.Tabla t : logico.tablas()) {
            if (claseDeTabla.containsKey(t.nombre())) continue;
            List<ModeloLogico.Columna> suPk = t.columnas().stream()
                    .filter(ModeloLogico.Columna::pk).toList();
            if (suPk.size() != 1 || !suPk.get(0).nombre().equals("id")) continue;
            asociativas.put(t.nombre(), t);
            claseDeTabla.put(t.nombre(), NombresJava.clase(t.nombre()));
        }

        // Que asociaciones posee cada tabla. Se calcula una vez para todas,
        // porque el lado INVERSO de una relacion vive en otra clase.
        Map<String, List<Asociacion>> asociacionesPorTabla = new LinkedHashMap<>();
        for (ModeloLogico.Tabla t : logico.tablas()) {
            asociacionesPorTabla.put(t.nombre(), asociacionesDe(t, porRelacion, claseDeTabla));
        }

        // Enumeraciones del modelo: un `enum` de Java por cada una. Las
        // advertencias ya las dio la derivacion; aca no se repiten.
        Map<String, String> tiposEnum = new LinkedHashMap<>();
        for (ModeloLogico.Enumeracion e : Enumeraciones.de(clases, atributos, new ArrayList<>()).values()) {
            String nombreEnum = NombresJava.clase(e.nombre());
            tiposEnum.put(e.nombre(), nombreEnum);
            archivos.put(ruta + "/domain/" + nombreEnum + ".java", enumeracion(pkg, nombreEnum, e));
        }

        // CONTRATO P2 -> P3 (ADR-021): el manifiesto se arma con los MISMOS
        // campos que el DTO, entidad por entidad, mientras se generan.
        List<ManifestDeApp.Entidad> manifiesto = new ArrayList<>();
        Map<String, List<String>> literalesDe = new HashMap<>();
        for (ModeloLogico.Enumeracion e : Enumeraciones.de(clases, atributos, new ArrayList<>()).values()) {
            literalesDe.put(NombresJava.clase(e.nombre()), e.literales());
        }
        Map<String, String> etiquetaDeColumna = new HashMap<>();
        for (UmlAttribute a : atributos) etiquetaDeColumna.putIfAbsent(Nombres.columna(a.getName()), a.getName());
        Map<String, String> etiquetaDeClase = new HashMap<>();
        vivas.forEach(c -> etiquetaDeClase.putIfAbsent(NombresJava.clase(c.getName()), c.getName()));

        // A-3 leido como OO: el multivaluado cuelga del duenio como coleccion
        // de valores en vez de ser una entidad aparte. La tabla es la misma.
        Map<UUID, List<ColeccionDeValores>> valoresPorClase = new LinkedHashMap<>();
        Set<String> tablasDeColeccion = new HashSet<>();
        for (UmlClass c : clases) {
            if (c.getDeletedAt() != null) continue;
            String tablaDuenio = Nombres.tabla(c.getName());
            for (UmlAttribute a : atributos) {
                if (!a.getClassId().equals(c.getId()) || !a.isMultivalued()) continue;
                if (!decisiones.esOO(Ambiguedad.TipoAmbiguedad.MULTIVALUED, a.getId(),
                        Ambiguedad.Lectura.DATA)) continue;
                String tablaValores = tablaDuenio + "_" + Nombres.columna(a.getName());
                valoresPorClase.computeIfAbsent(c.getId(), k -> new ArrayList<>())
                        .add(new ColeccionDeValores(NombresJava.campo(a.getName()),
                                tipoJavaDe(a.getDataType(), tiposEnum), tablaValores,
                                Nombres.columnaFk(tablaDuenio), Nombres.columna(a.getName()),
                                tiposEnum.containsKey(a.getDataType())));
                tablasDeColeccion.add(tablaValores);
            }
        }

        // REGLA 6: los metodos no van a la Entity ni al DDL, pero si al Service.
        Map<UUID, List<UmlMethod>> metodosPorClase = metodos.stream()
                .collect(Collectors.groupingBy(UmlMethod::getClassId));

        // REGLA 4, JOINED: quien hereda de quien se lee del esquema derivado. La
        // tabla hija tiene su `id` apuntando al `id` de la madre por una
        // GENERALIZATION; asi la entidad y el DDL no pueden discrepar.
        Map<String, ModeloLogico.Tabla> tablaPorNombre = new LinkedHashMap<>();
        logico.tablas().forEach(t -> tablaPorNombre.put(t.nombre(), t));
        Map<String, String> madreDe = new HashMap<>();
        for (ModeloLogico.Tabla t : logico.tablas()) {
            for (ModeloLogico.Columna c : t.columnas()) {
                if (!c.pk() || !"id".equals(c.nombre()) || c.referencia() == null || c.relationId() == null) continue;
                UmlRelationship r = porRelacion.get(c.relationId());
                if (r != null && r.getKind() == RelationKind.GENERALIZATION) {
                    madreDe.put(t.nombre(), c.referencia().substring(0, c.referencia().indexOf('(')));
                }
            }
        }
        Set<String> conHijas = new HashSet<>(madreDe.values());
        Map<String, UUID> claseIdDeTabla = new HashMap<>();
        vivas.forEach(c -> claseIdDeTabla.put(Nombres.tabla(c.getName()), c.getId()));

        // REGLA 5: cada clase de valor usada en alguna tabla sale una vez como
        // @Embeddable, con su DTO. Sus tipos se leen de la primera tabla que la usa.
        Set<String> valoresEmitidos = new HashSet<>();
        for (ModeloLogico.Tabla t : logico.tablas()) {
            for (ModeloLogico.Incrustado i : t.incrustados()) {
                String nombreValor = NombresJava.clase(i.clase());
                if (!valoresEmitidos.add(nombreValor)) continue;
                List<Campo> camposValor = camposDeValor(t, i);
                camposDeValores.put(nombreValor, camposValor);
                archivos.put(ruta + "/domain/" + nombreValor + ".java", mongo
                        ? PlantillasMongo.valorEmbebido(pkg, nombreValor, camposValor)
                        : valorEmbebido(pkg, nombreValor, camposValor));
                archivos.put(ruta + "/web/dto/" + nombreValor + "Dto.java", dtoDeValor(pkg, nombreValor, camposValor));
            }
        }

        // REGLA 4, SINGLE_TABLE: las clases que viven en la tabla de su raiz.
        Map<String, MiembroUnico> miembroDe = new HashMap<>();
        for (ModeloLogico.Tabla t : logico.tablas()) {
            if (t.tablaUnica() == null) continue;
            t.tablaUnica().miembros().forEach(m -> miembroDe.put(m.clase(), new MiembroUnico(t, m)));
        }

        int entidades = 0;
        Set<String> conEntity = new HashSet<>();
        for (UmlClass clase : vivas) {
            String tabla = Nombres.tabla(clase.getName());
            MiembroUnico unico = miembroDe.get(clase.getName());
            ModeloLogico.Tabla t = unico != null ? unico.tabla() : tablaPorNombre.get(tabla);
            if (t == null) continue;

            List<ModeloLogico.Columna> pks = t.columnas().stream().filter(ModeloLogico.Columna::pk).toList();
            if (pks.size() != 1 || !pks.get(0).nombre().equals("id")) continue;   // se avisa mas abajo

            String nombreJava = NombresJava.clase(clase.getName());
            List<Coleccion> colecciones = coleccionesDe(nombreJava, logico, asociacionesPorTabla,
                    porRelacion, claseDeTabla);

            Herencia herencia;
            List<Asociacion> asociaciones;
            List<Campo> propios;
            List<ModeloLogico.Incrustado> incrustadosPropios;
            List<ModeloLogico.Incrustado> incrustadosCompletos = new ArrayList<>();
            List<Campo> campos = new ArrayList<>();
            List<Asociacion> asociacionesCompletas = new ArrayList<>();
            List<ColeccionDeValores> valores = new ArrayList<>();

            if (unico != null) {
                // SINGLE_TABLE: la tabla es la de la raiz; cada clase se queda
                // con las columnas que declaro. Las subclases no tienen
                // asociaciones propias: la derivacion no funde una jerarquia que
                // las tenga.
                ModeloLogico.Miembro m = unico.miembro();
                ModeloLogico.TablaUnica tu = t.tablaUnica();
                boolean esRaiz = m.madre() == null;
                boolean tieneHijas = tu.miembros().stream().anyMatch(x -> clase.getName().equals(x.madre()));
                int largo = largoDe(t.columnas().stream().filter(c -> c.nombre().equals(tu.discriminador()))
                        .findFirst().map(ModeloLogico.Columna::tipo).orElse("varchar(31)"));
                herencia = new Herencia(esRaiz ? null : NombresJava.clase(m.madre()), esRaiz,
                        clase.isAbstract() && tieneHijas, true, tu.discriminador(), largo, m.valor());
                asociaciones = esRaiz ? asociacionesPorTabla.getOrDefault(t.nombre(), List.of()) : List.of();
                // Las subclases no tienen valores embebidos propios: la derivacion
                // no funde una jerarquia que los tenga. Los de la tabla son de la raiz.
                incrustadosPropios = esRaiz ? t.incrustados() : List.of();
                propios = sinIncrustadas(soloDe(camposDe(t, asociaciones), m), t.incrustados());

                // De la raiz a esta clase, cada nivel aporta sus columnas.
                LinkedList<ModeloLogico.Miembro> linaje = new LinkedList<>();
                for (ModeloLogico.Miembro x = m; x != null; ) {
                    linaje.addFirst(x);
                    String madreNombre = x.madre();
                    x = madreNombre == null ? null : miembroDe.get(madreNombre).miembro();
                }
                List<Asociacion> deLaRaiz = asociacionesPorTabla.getOrDefault(t.nombre(), List.of());
                for (ModeloLogico.Miembro nivel : linaje) {
                    campos.addAll(sinIncrustadas(soloDe(camposDe(t, deLaRaiz), nivel), t.incrustados()));
                }
                incrustadosCompletos.addAll(t.incrustados());
                asociacionesCompletas.addAll(deLaRaiz);
                valores.addAll(valoresPorClase.getOrDefault(claseIdDeTabla.get(t.nombre()), List.of()));
            } else {
                asociaciones = asociacionesPorTabla.getOrDefault(tabla, List.of());
                String madre = madreDe.get(tabla);
                herencia = Herencia.joined(
                        madre == null ? null : claseDeTabla.get(madre),
                        madre == null && conHijas.contains(tabla),
                        clase.isAbstract() && conHijas.contains(tabla));
                // La hija NO declara su id: lo hereda de la raiz. Declararlo otra vez
                // es lo que la volvia una entidad suelta con su propio @GeneratedValue.
                incrustadosPropios = t.incrustados();
                propios = sinIncrustadas(camposDe(t, asociaciones), t.incrustados()).stream()
                        .filter(c -> madre == null || !c.esId()).toList();

                // El DTO y el Service de una hija trabajan con TODO lo que la hija
                // es: sus campos y los heredados. Sin esto, crear un Empleado no
                // podia cargarle el nombre que vive en Persona.
                List<String> cadena = cadenaDeTablas(tabla, madreDe, advertencias);
                for (String nivel : cadena) {
                    List<Asociacion> delNivel = asociacionesPorTabla.getOrDefault(nivel, List.of());
                    ModeloLogico.Tabla tablaNivel = tablaPorNombre.get(nivel);
                    sinIncrustadas(camposDe(tablaNivel, delNivel), tablaNivel.incrustados()).stream()
                            .filter(c -> nivel.equals(cadena.get(0)) || !c.esId())
                            .forEach(campos::add);
                    incrustadosCompletos.addAll(tablaNivel.incrustados());
                    asociacionesCompletas.addAll(delNivel);
                    UUID idNivel = claseIdDeTabla.get(nivel);
                    if (idNivel != null) valores.addAll(valoresPorClase.getOrDefault(idNivel, List.of()));
                }
            }

            unidades.add(new Unidad(nombreJava, clase.getName(), tabla, t.nombre(), propios, asociaciones,
                    colecciones, valoresPorClase.getOrDefault(clase.getId(), List.of()), herencia,
                    incrustadosPropios, campos, asociacionesCompletas, valores, incrustadosCompletos,
                    stubsDe(clase.getName(), nombreJava,
                            metodosPorClase.getOrDefault(clase.getId(), List.of()),
                            advertencias, tiposEnum, pkg)));
            manifiesto.add(new ManifestDeApp.Entidad(nombreJava, clase.getName(), recursoDe(tabla),
                    herencia.abstracta(), herencia.madre(),
                    camposDelManifiesto(campos, asociacionesCompletas, valores, incrustadosCompletos,
                            literalesDe, etiquetaDeColumna, etiquetaDeClase),
                    relacionesDelManifiesto(asociacionesCompletas, colecciones)));
            conEntity.add(tabla);
            conEntity.add(t.nombre());
            entidades++;
        }

        for (var entrada : asociativas.entrySet()) {
            String tabla = entrada.getKey();
            ModeloLogico.Tabla t = entrada.getValue();
            String nombreJava = claseDeTabla.get(tabla);
            List<Asociacion> asociaciones = asociacionesPorTabla.getOrDefault(tabla, List.of());
            List<Campo> campos = camposDe(t, asociaciones);

            // Sin metodos: una clase asociativa no sale de ninguna clase UML,
            // asi que no hay firmas del modelo que aterrizar (Regla 6).
            unidades.add(new Unidad(nombreJava, tabla.replace('_', ' '), tabla, tabla, campos, asociaciones,
                    coleccionesDe(nombreJava, logico, asociacionesPorTabla, porRelacion, claseDeTabla),
                    List.of(), Herencia.NINGUNA, List.of(), campos, asociaciones, List.of(), List.of(),
                    Stubs.NINGUNO));
            manifiesto.add(new ManifestDeApp.Entidad(nombreJava, tabla.replace('_', ' '), recursoDe(tabla),
                    false, null,
                    camposDelManifiesto(campos, asociaciones, List.of(), List.of(),
                            literalesDe, etiquetaDeColumna, etiquetaDeClase),
                    relacionesDelManifiesto(asociaciones,
                            coleccionesDe(nombreJava, logico, asociacionesPorTabla, porRelacion, claseDeTabla))));
            conEntity.add(tabla);
            entidades++;
        }

        // REGLA 10: toda tabla que quedo sin Entity se declara. Saltearla en
        // silencio es lo que hace que alguien descubra el hueco en la demo.
        for (ModeloLogico.Tabla t : logico.tablas()) {
            if (conEntity.contains(t.nombre())) continue;
            // Una tabla de @ElementCollection no necesita Entity: esa es
            // exactamente la lectura que el equipo eligio.
            if (tablasDeColeccion.contains(t.nombre())) continue;
            boolean pkCompuesta = t.columnas().stream().filter(ModeloLogico.Columna::pk).count() > 1;
            advertencias.add("La tabla " + t.nombre() + " (" + t.origen() + ") esta en el DDL pero"
                    + (pkCompuesta
                            ? " tiene clave primaria compuesta y todavia no genera Entity (haria falta @IdClass)."
                            : " todavia no genera Entity."));
        }

        Meta meta = Meta.para(unidades);
        Map<String, List<String>> literales = new HashMap<>(literalesDe);
        MapeoDocumental documental = MapeoDocumental.de(logico, unidades, porRelacion, claseDeTabla,
                camposDeValores, meta);

        // Lo que no depende de la base: la API, sus errores y sus pruebas.
        archivos.put("pom.xml", mongo ? PlantillasMongo.pom(opciones) : pom(opciones));
        archivos.put(ruta + "/GeneratedApplication.java", aplicacion(pkg));
        archivos.put(ruta + "/config/CorsConfig.java", PlantillasProduccion.cors(pkg));
        archivos.put("src/main/resources/static/index.html", PlantillaConsola.html(meta)); // la consola de prueba (GET /), T-12
        PlantillasProduccion.comunes(pkg, ruta).forEach(archivos::put);
        for (Unidad u : unidades) {
            archivos.put(ruta + "/web/dto/" + u.clase() + "Dto.java",
                    dto(pkg, u.clase(), u.campos(), u.asociaciones(), u.valores(), u.incrustados(), meta));
            archivos.put(ruta + "/web/" + u.clase() + "Controller.java",
                    PlantillasProduccion.controlador(pkg, u));
            archivos.put("src/test/java/" + pkg.replace('.', '/') + "/web/" + u.clase() + "ControllerTest.java",
                    PlantillasProduccion.prueba(pkg, u, camposDeValores, literales));
        }

        if (mongo) {
            archivos.put("src/main/resources/application.yml", PlantillasMongo.configuracion(opciones));
            PlantillasMongo.archivos(pkg, ruta, unidades, documental, meta).forEach(archivos::put);
            archivos.put(MapeoDocumental.RUTA, documental.json());
            advertencias.addAll(documental.vista().advertencias());
        } else {
            archivos.put("src/main/resources/application.yml", configuracion(opciones));
            archivos.put(RUTA_DDL, ddl(logico));
            archivos.put(RUTA_AUDITORIA, auditoria(unidades, meta));
            for (Unidad u : unidades) {
                archivos.put(ruta + "/domain/" + u.clase() + ".java",
                        entidad(pkg, u.clase(), u.tabla(), u.propios(), u.asociacionesPropias(), u.colecciones(),
                                u.valoresPropios(), u.herencia(), u.incrustadosPropios(), meta));
                archivos.put(ruta + "/repository/" + u.clase() + "Repository.java",
                        repositorio(pkg, u.clase(), u.asociaciones()));
                archivos.put(ruta + "/service/" + u.clase() + "Service.java",
                        servicio(pkg, u.clase(), u.campos(), u.asociaciones(), u.valores(), u.incrustados(),
                                u.stubs(), u.instanciable(), meta, referenciasA(u, unidades, logico)));
            }
            Set<String> abstractas = new HashSet<>();
            unidades.stream().filter(u -> u.herencia().abstracta()).forEach(u -> abstractas.add(u.tablaFisica()));
            String ejemplo = DatosDeEjemplo.sql(logico, conEntity, abstractas);
            if (ejemplo != null) archivos.put(DatosDeEjemplo.RUTA, ejemplo);
        }

        PlantillasProduccion.despliegue(opciones).forEach(archivos::put);
        archivos.put(ManifestDeApp.RUTA, ManifestDeApp.json(opciones.nombreProyecto(), manifiesto));
        archivos.put("README.md", readme(opciones, entidades, advertencias, meta));
        return new Resultado(new ProyectoGenerado(opciones.nombreProyecto(), archivos, advertencias), documental);
    }

    /**
     * La Vista Documental (ADR-035): como guarda este modelo la variante
     * MongoDB. Sale del mismo analisis que el zip, asi que lo que se ve es lo
     * que se entrega.
     */
    public static ModeloDocumental documental(String nombreProyecto, List<UmlClass> clases,
                                              List<UmlAttribute> atributos, List<UmlMethod> metodos,
                                              List<UmlRelationship> relaciones, Decisiones decisiones) {
        return generarCompleto(Opciones.por(nombreProyecto, Almacen.MONGO), clases, atributos, metodos,
                relaciones, decisiones).documental().vista();
    }

    /** Donde va la migracion de auditoria. Aparte de V1: V1 es la base que se confirma (ADR-032). */
    public static final String RUTA_AUDITORIA = "src/main/resources/db/migration/V2__auditoria.sql";

    /**
     * Las columnas de auditoria y de version, en una migracion APARTE.
     *
     * V1 es exactamente el esquema que muestra la Vista Fisica y que el
     * equipo confirma: su huella viaja con la descarga (ADR-032). Si estas
     * columnas fueran ahi, toda base ya confirmada dejaria de coincidir sin
     * que nadie haya tocado el modelo. Solo las tablas raiz las llevan: una
     * hija JOINED las hereda por la misma fila de la madre.
     */
    private static String auditoria(List<Unidad> unidades, Meta meta) {
        StringBuilder sb = new StringBuilder("-- Auditoria y bloqueo optimista (ADR-034). Generado: no editar a mano.\n\n");
        Set<String> tablas = new LinkedHashSet<>();
        for (Unidad u : unidades) {
            if (u.herencia().madre() == null) tablas.add(u.tablaFisica());
        }
        for (String t : tablas) {
            sb.append("ALTER TABLE ").append(t).append(" ADD COLUMN ").append(meta.columnaVersion())
                    .append(" bigint DEFAULT 0 NOT NULL;\n")
              .append("ALTER TABLE ").append(t).append(" ADD COLUMN ").append(meta.columnaCreado())
                    .append(" timestamp DEFAULT CURRENT_TIMESTAMP NOT NULL;\n")
              .append("ALTER TABLE ").append(t).append(" ADD COLUMN ").append(meta.columnaActualizado())
                    .append(" timestamp DEFAULT CURRENT_TIMESTAMP NOT NULL;\n\n");
        }
        return sb.toString();
    }

    /**
     * Quien apunta a esta entidad, por la restriccion de clave foranea que
     * emite el DDL. Sirve para que el 409 de un borrado diga QUE la referencia
     * en vez de un "viola una restriccion" generico.
     */
    private static Map<String, String> referenciasA(Unidad destino, List<Unidad> unidades, ModeloLogico logico) {
        Map<String, String> salida = new LinkedHashMap<>();
        for (ModeloLogico.Tabla t : logico.tablas()) {
            for (ModeloLogico.Columna c : t.columnas()) {
                if (c.referencia() == null) continue;
                String tablaApuntada = c.referencia().substring(0, c.referencia().indexOf('('));
                if (!tablaApuntada.equals(destino.tablaFisica())) continue;
                String quien = unidades.stream().filter(u -> u.tablaFisica().equals(t.nombre())
                                && u.herencia().madre() == null)
                        .map(Unidad::clase).findFirst().orElse(t.nombre());
                salida.put(nombreDeFk(t, c), quien + "." + campoDeFk(c.nombre()));
            }
        }
        return salida;
    }

    // ------------------------------------------------------------ MANIFIESTO

    /**
     * Los campos del manifiesto, en el mismo orden y con las mismas claves JSON
     * que los componentes del DTO. El `id` no entra: lo genera la base (ADR-007)
     * y nadie lo dicta.
     */
    private static List<ManifestDeApp.Campo> camposDelManifiesto(List<Campo> campos,
                                                                 List<Asociacion> asociaciones,
                                                                 List<ColeccionDeValores> valores,
                                                                 List<ModeloLogico.Incrustado> incrustados,
                                                                 Map<String, List<String>> literalesDe,
                                                                 Map<String, String> etiquetaDeColumna,
                                                                 Map<String, String> etiquetaDeClase) {
        List<ManifestDeApp.Campo> salida = new ArrayList<>();
        for (Campo c : campos) {
            if (c.esId()) continue;
            ModeloLogico.Rango r = c.rango();
            salida.add(new ManifestDeApp.Campo(c.nombreJava(),
                    etiquetaDeColumna.getOrDefault(c.columna(), c.nombreJava()),
                    ManifestDeApp.tipoDe(c.tipoJava(), c.enumerado()),
                    !c.nullable() || c.obligatorio(), c.unico(),
                    "String".equals(c.tipoJava()) && !c.enumerado() ? c.largo() : null,
                    c.enumerado() ? literalesDe.get(c.tipoJava()) : null,
                    r == null ? null : r.minimo(), r == null ? null : r.maximo(),
                    null, null, List.of()));
        }
        // Las referencias viajan como id (igual que en el DTO). Tambien se las
        // nombra por la entidad apuntada: "con aeropuerto X" sirve para `origen`
        // y para `destino`, y es justo la ambiguedad que el motor tiene que ver.
        for (Asociacion a : asociaciones) {
            salida.add(new ManifestDeApp.Campo(a.campo() + "Id", a.campo(), "reference", !a.nullable(),
                    a.unoAUno(), null, null, null, null, a.claseDestino(), null,
                    List.of(etiquetaDeClase.getOrDefault(a.claseDestino(), a.claseDestino()))));
        }
        for (ModeloLogico.Incrustado i : incrustados) {
            salida.add(new ManifestDeApp.Campo(NombresJava.campo(i.campo()), i.campo(), "embedded",
                    i.obligatorio(), false, null, null, null, null, NombresJava.clase(i.clase()), null,
                    List.of()));
        }
        for (ColeccionDeValores v : valores) {
            salida.add(new ManifestDeApp.Campo(v.campo(), etiquetaDeColumna.getOrDefault(v.columna(), v.campo()),
                    "list", false, false, null, v.enumerado() ? literalesDe.get(v.tipoElemento()) : null,
                    null, null, null, ManifestDeApp.tipoDe(v.tipoElemento(), v.enumerado()), List.of()));
        }
        return salida;
    }

    private static List<ManifestDeApp.Relacion> relacionesDelManifiesto(List<Asociacion> asociaciones,
                                                                       List<Coleccion> colecciones) {
        List<ManifestDeApp.Relacion> salida = new ArrayList<>();
        for (Asociacion a : asociaciones) {
            salida.add(new ManifestDeApp.Relacion(a.campo(), a.claseDestino(), false, a.campo() + "Id", null));
        }
        for (Coleccion c : colecciones) {
            salida.add(new ManifestDeApp.Relacion(c.campo(), c.claseElemento(), !c.unoAUno(), null,
                    c.mappedBy() + "Id"));
        }
        return salida;
    }

    /** La misma ruta que `@RequestMapping` en {@link #controlador}. */
    private static String recursoDe(String tabla) {
        return "/api/" + tabla.replace('_', '-');
    }

    // --------------------------------------------------------------- CAMPOS

    /** @param enumerado el tipo es un enum del modelo: va con @Enumerated(STRING) */
    /**
     * @param obligatorio el modelo lo pide obligatorio aunque la columna admita
     *                    NULL (SINGLE_TABLE): el @NotNull va igual en el DTO
     */
    /**
     * Si esta columna de clave foranea necesita su propio indice.
     *
     * PostgreSQL NO indexa las FK solo: sin indice, cada JOIN por la FK y cada
     * borrado del lado referenciado recorren la tabla entera. Se saltea cuando
     * ya la cubre otro indice --la primera columna de la PK, o un UNIQUE--.
     *
     * Vive aca y no duplicada en la Vista Fisica: si cada una decidiera por su
     * cuenta, la pantalla mostraria indices que el script no crea.
     */
    public static boolean necesitaIndice(ModeloLogico.Tabla tabla, ModeloLogico.Columna columna) {
        if (columna.referencia() == null) return false;
        List<String> pks = tabla.columnas().stream().filter(ModeloLogico.Columna::pk)
                .map(ModeloLogico.Columna::nombre).toList();
        boolean cubierta = (!pks.isEmpty() && pks.get(0).equals(columna.nombre())) || columna.unique();
        return !cubierta;
    }

    /** El nombre del indice que emite el DDL para esa columna. */
    public static String nombreDeIndice(ModeloLogico.Tabla tabla, ModeloLogico.Columna columna) {
        return Nombres.identificador("idx_" + tabla.nombre() + "_" + columna.nombre());
    }

    /** El nombre de la restriccion de clave foranea que emite el DDL. */
    public static String nombreDeFk(ModeloLogico.Tabla tabla, ModeloLogico.Columna columna) {
        return Nombres.identificador("fk_" + tabla.nombre() + "_" + columna.nombre());
    }

    record Campo(String nombreJava, String columna, String tipoJava, boolean nullable,
                         boolean esId, Integer largo, boolean enumerado, boolean obligatorio,
                         ModeloLogico.Rango rango, boolean unico) {
        Campo comoObligatorio() {
            return new Campo(nombreJava, columna, tipoJava, nullable, esId, largo, enumerado, true, rango, unico);
        }
    }

    /** REGLA 7: el lado que tiene la FK. `@ManyToOne` (o `@OneToOne` en 1:1). */
    record Asociacion(String campo, String claseDestino, String columna,
                      boolean nullable, boolean unoAUno, RelationKind kind) { }

    /**
     * El repositorio con el que el Service de `clase` resuelve una asociacion.
     *
     * Es uno por CLASE DESTINO y no uno por asociacion, porque una clase puede
     * tener DOS asociaciones a la misma: el modelo 07 tiene Vuelo con `origen`
     * y `destino`, los dos a Aeropuerto. Con un repositorio por asociacion el
     * Service emitia dos campos y dos parametros llamados `aeropuertoRepository`
     * y javac cortaba con "already defined". Uno solo alcanza: el Service solo
     * los usa para `getReferenceById`, y un repositorio de Spring Data no
     * guarda nada de la relacion por la que se lo consulta.
     *
     * Si la asociacion es reflexiva (Empleado -> jefe, del mismo 07) el
     * repositorio ya esta inyectado como `repositorio`, el de la propia clase:
     * inyectarlo otra vez con otro nombre seria el mismo bean dos veces.
     */
    private static String repositorioDe(String clase, String claseDestino) {
        return clase.equals(claseDestino) ? "repositorio" : NombresJava.campo(claseDestino) + "Repository";
    }

    /**
     * Donde esta la clase en su jerarquia (Regla 4, JOINED).
     *
     * @param madre     clase Java de la que hereda, o null
     * @param raiz      tiene hijas y no tiene madre: lleva @Inheritance
     * @param abstracta raiz abstracta: no se puede instanciar, asi que su API
     *                  solo lista, obtiene y elimina
     */
    record Herencia(String madre, boolean raiz, boolean abstracta,
                            boolean tablaUnica, String discriminador, int largoDiscriminador,
                            String valor) {
        static final Herencia NINGUNA = new Herencia(null, false, false, false, null, 0, null);

        static Herencia joined(String madre, boolean raiz, boolean abstracta) {
            return new Herencia(madre, raiz, abstracta, false, null, 0, null);
        }
    }

    /** Una clase de una jerarquia SINGLE_TABLE, con la tabla unica que la guarda. */
    private record MiembroUnico(ModeloLogico.Tabla tabla, ModeloLogico.Miembro miembro) { }

    /** Los campos que NO son columnas de un valor embebido: esas viven dentro del @Embeddable. */
    private static List<Campo> sinIncrustadas(List<Campo> campos, List<ModeloLogico.Incrustado> incrustados) {
        Set<String> suyas = new HashSet<>();
        incrustados.forEach(i -> i.columnas().forEach(c -> suyas.add(c.columna())));
        return campos.stream().filter(c -> !suyas.contains(c.columna())).toList();
    }

    /**
     * Los campos del @Embeddable, con el nombre de su ATRIBUTO (no el de la
     * columna prefijada del duenio): `calle`, no `direccionCalle`.
     */
    private static List<Campo> camposDeValor(ModeloLogico.Tabla t, ModeloLogico.Incrustado i) {
        List<Campo> campos = new ArrayList<>();
        for (ModeloLogico.ColumnaIncrustada ci : i.columnas()) {
            ModeloLogico.Columna c = t.columnas().stream().filter(x -> x.nombre().equals(ci.columna()))
                    .findFirst().orElseThrow();
            boolean enumerado = c.enumeracion() != null;
            campos.add(new Campo(NombresJava.campo(ci.atributo()), Nombres.columna(ci.atributo()),
                    enumerado ? NombresJava.clase(c.enumeracion().nombre()) : javaDe(c.tipo()),
                    c.nullable(), false, largoDe(c.tipo()), enumerado, false, c.rango(), c.unique()));
        }
        return campos;
    }

    /** Los campos de la tabla unica que declaro este miembro, con su obligatorio. */
    private static List<Campo> soloDe(List<Campo> todos, ModeloLogico.Miembro m) {
        Set<String> suyas = new HashSet<>(m.columnas());
        Set<String> obligatorias = new HashSet<>(m.obligatorias());
        return todos.stream()
                .filter(c -> suyas.contains(c.columna()))
                .map(c -> obligatorias.contains(c.columna()) ? c.comoObligatorio() : c)
                .toList();
    }

    /**
     * La tabla y sus ancestros, de la raiz a la hoja. Un ciclo (A hereda de B
     * y B de A) se corta y se avisa: seguirlo seria un bucle infinito.
     */
    private static List<String> cadenaDeTablas(String tabla, Map<String, String> madreDe,
                                               List<String> advertencias) {
        LinkedList<String> cadena = new LinkedList<>();
        Set<String> vistas = new HashSet<>();
        for (String actual = tabla; actual != null; actual = madreDe.get(actual)) {
            if (!vistas.add(actual)) {
                advertencias.add("La herencia de " + tabla + " es circular: se corto en " + actual
                        + " (Regla 10).");
                break;
            }
            cadena.addFirst(actual);
        }
        return cadena;
    }

    /** A-3 leido como OO: una coleccion de VALORES, no de entidades. */
    record ColeccionDeValores(String campo, String tipoElemento, String tabla,
                                      String joinColumn, String columna, boolean enumerado) { }

    /** El lado inverso: la coleccion, con el cascade que pide el tipo de relacion. */
    record Coleccion(String campo, String claseElemento, String mappedBy,
                             String cascade, boolean unoAUno) { }

    private static List<Campo> camposDe(ModeloLogico.Tabla t, List<Asociacion> asociaciones) {
        Set<String> deAsociacion = asociaciones.stream()
                .map(Asociacion::columna).collect(Collectors.toSet());
        List<Campo> campos = new ArrayList<>();
        for (ModeloLogico.Columna c : t.columnas()) {
            // Las columnas que se volvieron asociacion ya no van como UUID suelto.
            if (deAsociacion.contains(c.nombre())) continue;
            boolean enumerado = c.enumeracion() != null;
            campos.add(new Campo(NombresJava.campo(c.nombre()), c.nombre(),
                    enumerado ? NombresJava.clase(c.enumeracion().nombre()) : javaDe(c.tipo()),
                    c.nullable(), c.pk() && c.nombre().equals("id"), largoDe(c.tipo()), enumerado, false,
                    c.rango(), c.unique()));
        }
        return campos;
    }

    /**
     * Asociaciones que PERTENECEN a esta tabla: las columnas FK que salieron
     * de una relacion. Se excluye la herencia (es `extends`, no un campo) y
     * las FK que forman parte de la PK de una entidad debil, que necesitarian
     * `@MapsId`.
     */
    private static List<Asociacion> asociacionesDe(ModeloLogico.Tabla t,
                                                   Map<UUID, UmlRelationship> porRelacion,
                                                   Map<String, String> claseDeTabla) {
        List<Asociacion> asociaciones = new ArrayList<>();
        for (ModeloLogico.Columna c : t.columnas()) {
            if (c.relationId() == null || c.referencia() == null || c.pk()) continue;
            UmlRelationship r = porRelacion.get(c.relationId());
            if (r == null || r.getKind() == RelationKind.GENERALIZATION) continue;
            String tablaDestino = c.referencia().substring(0, c.referencia().indexOf('('));
            String claseDestino = claseDeTabla.get(tablaDestino);
            if (claseDestino == null) continue;
            asociaciones.add(new Asociacion(campoDeFk(c.nombre()), claseDestino, c.nombre(),
                    c.nullable(), c.unique(), r.getKind()));
        }
        return asociaciones;
    }

    /**
     * El lado INVERSO: por cada asociacion que apunta a esta clase, una
     * coleccion con su `mappedBy`. Es lo que convierte dos columnas sueltas en
     * una relacion navegable en los dos sentidos.
     */
    private static List<Coleccion> coleccionesDe(String claseJava, ModeloLogico logico,
                                                 Map<String, List<Asociacion>> asociacionesPorTabla,
                                                 Map<UUID, UmlRelationship> porRelacion,
                                                 Map<String, String> claseDeTabla) {
        List<Coleccion> colecciones = new ArrayList<>();
        Set<String> usados = new HashSet<>();

        for (ModeloLogico.Tabla t : logico.tablas()) {
            String claseDuenia = claseDeTabla.get(t.nombre());
            if (claseDuenia == null) continue;

            for (ModeloLogico.Columna c : t.columnas()) {
                if (c.relationId() == null || c.referencia() == null || c.pk()) continue;
                String tablaApuntada = c.referencia().substring(0, c.referencia().indexOf('('));
                if (!claseJava.equals(claseDeTabla.get(tablaApuntada))) continue;

                UmlRelationship r = porRelacion.get(c.relationId());
                if (r == null || r.getKind() == RelationKind.GENERALIZATION) continue;

                String mappedBy = campoDeFk(c.nombre());
                String base = c.unique() ? NombresJava.campo(claseDuenia)
                        : NombresJava.campo(claseDuenia) + "s";
                String campo = base;
                int i = 2;
                while (!usados.add(campo)) campo = base + i++;   // reflexivas y dobles

                colecciones.add(new Coleccion(campo, claseDuenia, mappedBy,
                        cascadeDe(r.getKind()), c.unique()));
            }
        }
        return colecciones;
    }

    /** "libro_id" -> "libro". El sufijo lo puso la Regla 3, aca estorba. */
    static String campoDeFk(String columna) {
        String base = columna.endsWith("_id") ? columna.substring(0, columna.length() - 3) : columna;
        return NombresJava.campo(base);
    }

    /**
     * REGLA 7: el cascade sale del TIPO de relacion, no de una preferencia.
     * La composicion se lleva a la parte; la agregacion solo propaga altas y
     * cambios, porque la parte sobrevive al todo.
     */
    private static String cascadeDe(RelationKind kind) {
        return switch (kind) {
            case COMPOSITION -> ", cascade = CascadeType.ALL, orphanRemoval = true";
            case AGGREGATION -> ", cascade = { CascadeType.PERSIST, CascadeType.MERGE }";
            default -> "";
        };
    }

    /** Del tipo SQL al de Java: asi la entidad no puede discrepar del DDL. */
    private static String javaDe(String tipoSql) {
        if (tipoSql.startsWith("varchar") || tipoSql.equals("text")) return "String";
        return switch (tipoSql) {
            case "uuid" -> "UUID";
            case "integer" -> "Integer";
            case "bigint" -> "Long";
            case "boolean" -> "Boolean";
            case "date" -> "LocalDate";
            case "timestamp" -> "LocalDateTime";
            default -> tipoSql.startsWith("numeric") ? "BigDecimal" : "String";
        };
    }

    private static Integer largoDe(String tipoSql) {
        if (!tipoSql.startsWith("varchar(")) return null;
        return Integer.valueOf(tipoSql.substring(8, tipoSql.length() - 1));
    }

    /** UUID queda afuera: las plantillas ya lo importan siempre (el id lo es). */
    private static String importsDe(List<Campo> campos) {
        Set<String> imports = new TreeSet<>();
        for (Campo c : campos) {
            String imp = TipoJava.importDe(c.tipoJava());
            if (imp != null && !imp.equals("java.util.UUID")) imports.add("import " + imp + ";");
        }
        return String.join("\n", imports);
    }

    // ------------------------------------------------------------ PLANTILLAS

    private static String pom(Opciones o) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
                    <modelVersion>4.0.0</modelVersion>

                    <parent>
                        <groupId>org.springframework.boot</groupId>
                        <artifactId>spring-boot-starter-parent</artifactId>
                        <version>3.5.0</version>
                        <relativePath/>
                    </parent>

                    <groupId>com.uagrm</groupId>
                    <artifactId>{{artefacto}}</artifactId>
                    <version>0.1.0</version>
                    <name>{{proyecto}}</name>

                    <properties>
                        <java.version>21</java.version>
                    </properties>

                    <dependencies>
                        <dependency>
                            <groupId>org.springframework.boot</groupId>
                            <artifactId>spring-boot-starter-web</artifactId>
                        </dependency>
                        <dependency>
                            <groupId>org.springframework.boot</groupId>
                            <artifactId>spring-boot-starter-data-jpa</artifactId>
                        </dependency>
                        <dependency>
                            <groupId>org.springframework.boot</groupId>
                            <artifactId>spring-boot-starter-validation</artifactId>
                        </dependency>
                        <!-- Base por defecto: en memoria, sin instalar nada.
                             Es lo que permite probar este backend en cualquier
                             maquina sin Docker ni PostgreSQL levantado. -->
                        <dependency>
                            <groupId>com.h2database</groupId>
                            <artifactId>h2</artifactId>
                            <scope>runtime</scope>
                        </dependency>
                        <dependency>
                            <groupId>org.postgresql</groupId>
                            <artifactId>postgresql</artifactId>
                            <scope>runtime</scope>
                        </dependency>
                        <dependency>
                            <groupId>org.flywaydb</groupId>
                            <artifactId>flyway-core</artifactId>
                        </dependency>
                        <dependency>
                            <groupId>org.flywaydb</groupId>
                            <artifactId>flyway-database-postgresql</artifactId>
                        </dependency>
                        <!-- H2 no lleva modulo aparte: Flyway 10 saco a PostgreSQL y
                             compania de `flyway-core`, pero H2 sigue adentro. No
                             existe ningun `flyway-database-h2` que agregar. -->
                        <!-- Swagger UI en /swagger-ui.html: probar la API sin
                             escribir un solo cliente. Es la red de seguridad
                             de la demo si el telefono o la red fallan.

                             2.8.x y no 2.6.0: con Spring Boot 3.5 (Framework 6.2)
                             la 2.6.0 carga la pagina pero /v3/api-docs responde 500
                             con NoSuchMethodError sobre ControllerAdviceBean. O sea
                             que "anda" hasta que alguien la abre. -->
                        <dependency>
                            <groupId>org.springdoc</groupId>
                            <artifactId>springdoc-openapi-starter-webmvc-ui</artifactId>
                            <version>2.8.9</version>
                        </dependency>
                {{comunes}}    </dependencies>

                    <build>
                        <plugins>
                            <plugin>
                                <groupId>org.springframework.boot</groupId>
                                <artifactId>spring-boot-maven-plugin</artifactId>
                            </plugin>
                        </plugins>
                    </build>
                </project>
                """
                .replace("{{comunes}}", PlantillasProduccion.dependenciasComunes())
                .replace("{{artefacto}}", artefacto(o))
                .replace("{{proyecto}}", o.nombreProyecto());
    }

    /** El artifactId de Maven: tambien es el nombre del jar y de la base. */
    static String artefacto(Opciones o) {
        return Nombres.identificador(o.nombreProyecto()).replace('_', '-');
    }

    private static String aplicacion(String pkg) {
        return """
                package {{pkg}};

                import org.springframework.boot.SpringApplication;
                import org.springframework.boot.autoconfigure.SpringBootApplication;

                @SpringBootApplication
                public class GeneratedApplication {
                    public static void main(String[] args) {
                        SpringApplication.run(GeneratedApplication.class, args);
                    }
                }
                """.replace("{{pkg}}", pkg);
    }

    /**
     * Tres perfiles (ADR-034):
     *   - `dev` (por defecto): H2 en memoria con datos de ejemplo. Arranca sin
     *     instalar nada, que es lo que hace que el zip se pruebe en cualquier
     *     maquina. `h2` sigue funcionando como alias.
     *   - `prod`: PostgreSQL. La base, el usuario, la clave, el puerto y los
     *     origenes CORS salen de variables de entorno; ninguna clave queda
     *     escrita en el jar.
     *   - `postgres`: el PostgreSQL local de desarrollo, como antes.
     */
    private static String configuracion(Opciones o) {
        return (PlantillasProduccion.yamlComun(o) + """
                spring:
                  application:
                    name: {{proyecto}}
                  profiles:
                    default: dev
                    group:
                      h2: dev
                  jpa:
                    hibernate:
                      # El esquema lo crea Flyway; Hibernate SOLO valida. Si el mapeo
                      # y el DDL no coinciden, la app no arranca (ADR-009).
                      ddl-auto: validate
                    open-in-view: false
                  flyway:
                    enabled: true

                ---
                spring:
                  config:
                    activate:
                      on-profile: dev
                  datasource:
                    # MODE=PostgreSQL para que el mismo DDL valga en las dos bases.
                    # DB_CLOSE_DELAY=-1 para que la base no se borre cuando el pool
                    # se queda sin conexiones abiertas.
                    url: jdbc:h2:mem:{{db}};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1
                    username: sa
                    password: ""
                  h2:
                    console:
                      enabled: true
                  flyway:
                    # `db/demo` solo se lee aca: los datos de ejemplo no ensucian
                    # una base de verdad.
                    locations: classpath:db/migration,classpath:db/demo
                logging:
                  level:
                    "{{pkg}}": DEBUG

                ---
                spring:
                  config:
                    activate:
                      on-profile: postgres
                  datasource:
                    # El PostgreSQL del docker-compose de ColabModel (puerto 55432).
                    # 127.0.0.1 y no localhost: en Windows, localhost puede ir por
                    # IPv6 a un PostgreSQL nativo distinto.
                    url: jdbc:postgresql://127.0.0.1:55432/{{db}}
                    username: colabmodel
                    password: colabmodel
                  flyway:
                    locations: classpath:db/migration

                ---
                # PRODUCCION: nada sensible queda escrito aca. Sin DB_URL, DB_USER o
                # DB_PASSWORD la aplicacion no arranca, en vez de arrancar contra
                # una base equivocada.
                spring:
                  config:
                    activate:
                      on-profile: prod
                  datasource:
                    url: ${DB_URL}
                    username: ${DB_USER}
                    password: ${DB_PASSWORD}
                  flyway:
                    locations: classpath:db/migration
                """
                + PlantillasProduccion.yamlProd(o))
                .replace("{{proyecto}}", o.nombreProyecto())
                .replace("{{pkg}}", o.paquete())
                .replace("{{db}}", Nombres.identificador(o.nombreProyecto()));
    }

    /**
     * El DDL en TRES pasadas: tablas, claves foraneas e indices.
     *
     * Antes cada FK iba en linea dentro de su CREATE TABLE, en el orden en que
     * salian las tablas. Si `ejemplar` referenciaba a `libro` y `ejemplar` se
     * creaba primero, PostgreSQL cortaba con "relation libro does not exist":
     * el proyecto COMPILABA pero NO ARRANCABA, porque Flyway corre esto al
     * iniciar. `mvn package` no lo podia detectar.
     *
     * Con las FK al final el orden de las tablas deja de importar, y tambien
     * funcionan las referencias cruzadas (A -> B y B -> A), que ningun orden
     * de CREATE TABLE puede resolver. Es lo mismo que hace `pg_dump`.
     */
    /**
     * El DDL de PostgreSQL. PUBLICO porque tiene un solo productor.
     *
     * La Vista Fisica muestra exactamente esto, no una version parecida: si
     * la pantalla derivara el esquema por su cuenta, lo que se demuestra
     * dejaria de ser lo que se entrega (mismo principio que la Vista Logica,
     * ADR-003).
     */
    public static String ddl(ModeloLogico logico) {
        StringBuilder sb = new StringBuilder("-- Generado desde el modelo conceptual. No editar a mano.\n\n");

        for (ModeloLogico.Tabla t : logico.tablas()) {
            sb.append("CREATE TABLE ").append(t.nombre()).append(" (\n");
            List<String> lineas = new ArrayList<>();
            for (ModeloLogico.Columna c : t.columnas()) {
                StringBuilder linea = new StringBuilder("    " + c.nombre() + " " + c.tipo());
                if (!c.nullable()) linea.append(" NOT NULL");
                if (c.unique()) linea.append(" UNIQUE");
                // REGLA 9, tercera capa: la base rechaza por su cuenta un literal
                // que no existe, aunque alguien escriba sin pasar por la API.
                if (c.rango() != null) {
                    linea.append(" CHECK (").append(c.rango().condicionSql(c.nombre())).append(")");
                }
                if (c.enumeracion() != null) {
                    linea.append(" CHECK (").append(c.nombre()).append(" IN (")
                            .append(c.enumeracion().literales().stream()
                                    .map(l -> "'" + l + "'").collect(Collectors.joining(", ")))
                            .append("))");
                }
                lineas.add(linea.toString());
            }
            List<String> pks = t.columnas().stream().filter(ModeloLogico.Columna::pk)
                    .map(ModeloLogico.Columna::nombre).toList();
            if (!pks.isEmpty()) lineas.add("    PRIMARY KEY (" + String.join(", ", pks) + ")");
            sb.append(String.join(",\n", lineas)).append("\n);\n\n");
        }

        StringBuilder fks = new StringBuilder();
        StringBuilder indices = new StringBuilder();
        for (ModeloLogico.Tabla t : logico.tablas()) {
            List<String> pks = t.columnas().stream().filter(ModeloLogico.Columna::pk)
                    .map(ModeloLogico.Columna::nombre).toList();
            for (ModeloLogico.Columna c : t.columnas()) {
                if (c.referencia() == null) continue;
                fks.append("ALTER TABLE ").append(t.nombre())
                        .append(" ADD CONSTRAINT ").append(nombreDeFk(t, c))
                        .append(" FOREIGN KEY (").append(c.nombre()).append(") REFERENCES ").append(c.referencia());
                if (c.onDelete() != null) fks.append(" ON DELETE ").append(c.onDelete());
                fks.append(";\n");

                // PostgreSQL NO indexa las FK solo. Sin indice, cada JOIN por la
                // FK y cada borrado del lado referenciado recorren la tabla
                // entera (ADR-009). Se saltea si ya lo cubre otro indice: la
                // primera columna de la PK o un UNIQUE.
                if (necesitaIndice(t, c)) {
                    indices.append("CREATE INDEX ")
                            .append(nombreDeIndice(t, c))
                            .append(" ON ").append(t.nombre()).append(" (").append(c.nombre()).append(");\n");
                }
            }
        }
        if (!fks.isEmpty()) {
            sb.append("-- Claves foraneas: despues de TODAS las tablas, asi el orden no importa.\n")
              .append(fks).append("\n");
        }
        if (!indices.isEmpty()) {
            sb.append("-- Indices sobre las claves foraneas (PostgreSQL no los crea solo).\n")
              .append(indices);
        }
        return sb.toString();
    }

    private static String entidad(String pkg, String clase, String tabla, List<Campo> campos,
                                  List<Asociacion> asociaciones, List<Coleccion> colecciones,
                                  List<ColeccionDeValores> valores, Herencia herencia,
                                  List<ModeloLogico.Incrustado> incrustados, Meta meta) {
        StringBuilder relaciones = new StringBuilder();
        StringBuilder accesoresRel = new StringBuilder();

        for (Asociacion a : asociaciones) {
            // REGLA 7: LAZY EXPLICITO. JPA trae EAGER en @ManyToOne y es el
            // bug clasico: una consulta se convierte en N+1 sin avisar.
            relaciones.append("    @").append(a.unoAUno() ? "OneToOne" : "ManyToOne")
                    .append("(fetch = FetchType.LAZY)\n")
                    .append("    @JoinColumn(name = \"").append(a.columna())
                    .append("\", nullable = ").append(a.nullable()).append(")\n")
                    .append("    private ").append(a.claseDestino()).append(" ").append(a.campo())
                    .append(";\n\n");
            accesoresRel.append(accesores(a.claseDestino(), a.campo()));
        }

        for (Coleccion c : colecciones) {
            String tipo = c.unoAUno() ? c.claseElemento() : "List<" + c.claseElemento() + ">";
            relaciones.append("    @").append(c.unoAUno() ? "OneToOne" : "OneToMany")
                    .append("(mappedBy = \"").append(c.mappedBy()).append("\"")
                    .append(c.cascade()).append(")\n")
                    .append("    private ").append(tipo).append(" ").append(c.campo())
                    .append(c.unoAUno() ? ";\n\n" : " = new ArrayList<>();\n\n");
            accesoresRel.append(accesores(tipo, c.campo()));
        }

        for (ColeccionDeValores v : valores) {
            // A-3, lectura OO: @ElementCollection. La tabla existe igual --1FN
            // no se negocia-- pero no es una entidad: es una lista de valores
            // que pertenece a esta fila y muere con ella.
            String tipo = "List<" + v.tipoElemento() + ">";
            relaciones.append("""
                        @ElementCollection
                        @CollectionTable(name = "{{tabla}}", joinColumns = @JoinColumn(name = "{{join}}"))
                    {{enumerado}}    @Column(name = "{{columna}}")
                        private {{tipo}} {{campo}} = new ArrayList<>();

                    """
                    .replace("{{enumerado}}", v.enumerado() ? "    @Enumerated(EnumType.STRING)\n" : "")
                    .replace("{{tabla}}", v.tabla())
                    .replace("{{join}}", v.joinColumn())
                    .replace("{{columna}}", v.columna())
                    .replace("{{tipo}}", tipo)
                    .replace("{{campo}}", v.campo()));
            accesoresRel.append(accesores(tipo, v.campo()));
        }

        for (ModeloLogico.Incrustado i : incrustados) {
            // REGLA 5: el valor vive en las columnas del duenio. Cada campo del
            // @Embeddable se redirige a su columna prefijada: asi el mismo
            // Direccion sirve para Cliente y para Proveedor.
            String tipo = NombresJava.clase(i.clase());
            String campo = NombresJava.campo(i.campo());
            List<String> overrides = new ArrayList<>();
            for (ModeloLogico.ColumnaIncrustada ci : i.columnas()) {
                overrides.add("        @AttributeOverride(name = \"" + NombresJava.campo(ci.atributo())
                        + "\", column = @Column(name = \"" + ci.columna() + "\"))");
            }
            relaciones.append("    @Embedded\n    @AttributeOverrides({\n")
                    .append(String.join(",\n", overrides)).append("\n    })\n")
                    .append("    private ").append(tipo).append(" ").append(campo).append(";\n\n");
            accesoresRel.append(accesores(tipo, campo));
        }

        return entidadTexto(pkg, clase, tabla, campos, relaciones.toString(), accesoresRel.toString(),
                !colecciones.isEmpty() || !valores.isEmpty(), herencia, meta);
    }

    /** REGLA 5: la clase de valor. Sin @Id y sin tabla: vive dentro de quien la use. */
    private static String valorEmbebido(String pkg, String nombre, List<Campo> campos) {
        StringBuilder cuerpo = new StringBuilder();
        StringBuilder acc = new StringBuilder();
        for (Campo c : campos) {
            if (c.enumerado()) cuerpo.append("    @Enumerated(EnumType.STRING)\n");
            String largo = c.largo() != null && ("String".equals(c.tipoJava()) || c.enumerado())
                    ? ", length = " + c.largo() : "";
            cuerpo.append("    @Column(name = \"").append(c.columna()).append("\"").append(largo).append(")\n")
                    .append("    private ").append(c.tipoJava()).append(" ").append(c.nombreJava()).append(";\n\n");
            acc.append(accesores(c.tipoJava(), c.nombreJava()));
        }
        return """
                package {{pkg}}.domain;

                import jakarta.persistence.*;
                {{imports}}

                /**
                 * REGLA 5: objeto de valor. No tiene identidad propia ni tabla: sus
                 * campos se guardan en las columnas de la entidad que lo contiene,
                 * que los redirige con @AttributeOverrides.
                 */
                @Embeddable
                public class {{nombre}} {

                {{cuerpo}}{{accesores}}}
                """
                .replace("{{pkg}}", pkg)
                .replace("{{imports}}", importsDe(campos))
                .replace("{{nombre}}", nombre)
                .replace("{{cuerpo}}", cuerpo.toString())
                .replace("{{accesores}}", acc.toString());
    }

    /** El DTO de un valor embebido, con su conversion en los dos sentidos. */
    private static String dtoDeValor(String pkg, String nombre, List<Campo> campos) {
        List<String> componentes = new ArrayList<>();
        Set<String> imports = new TreeSet<>();
        for (Campo c : campos) {
            StringBuilder comp = new StringBuilder("        ");
            if (!c.nullable()) comp.append("@NotNull ");
            if (c.largo() != null && "String".equals(c.tipoJava())) comp.append("@Size(max = ").append(c.largo()).append(") ");
            if (c.rango() != null) {
                ModeloLogico.Rango r = c.rango();
                if (r.minimo() != null) comp.append(r.entero() ? "@Min(" + ModeloLogico.Rango.texto(r.minimo()) + ") "
                        : "@DecimalMin(\"" + ModeloLogico.Rango.texto(r.minimo()) + "\") ");
                if (r.maximo() != null) comp.append(r.entero() ? "@Max(" + ModeloLogico.Rango.texto(r.maximo()) + ") "
                        : "@DecimalMax(\"" + ModeloLogico.Rango.texto(r.maximo()) + "\") ");
            }
            comp.append(c.tipoJava()).append(" ").append(c.nombreJava());
            componentes.add(comp.toString());
            String imp = TipoJava.importDe(c.tipoJava());
            if (imp != null) imports.add("import " + imp + ";");
            if (c.enumerado()) imports.add("import " + pkg + ".domain." + c.tipoJava() + ";");
        }
        String deEntidad = campos.stream().map(c -> "v." + NombresJava.getter(c.nombreJava()) + "()")
                .collect(Collectors.joining(", "));
        String aEntidad = campos.stream()
                .map(c -> "        v." + NombresJava.setter(c.nombreJava()) + "(" + c.nombreJava() + ");\n")
                .collect(Collectors.joining());
        return """
                package {{pkg}}.web.dto;

                import {{pkg}}.domain.{{nombre}};
                import jakarta.validation.constraints.*;
                {{imports}}

                /** DTO del valor embebido {{nombre}}: viaja anidado dentro del DTO de su duenio. */
                public record {{nombre}}Dto(
                {{componentes}}
                ) {
                    public static {{nombre}}Dto de({{nombre}} v) {
                        return new {{nombre}}Dto({{deEntidad}});
                    }

                    public {{nombre}} aEntidad() {
                        {{nombre}} v = new {{nombre}}();
                {{aEntidad}}        return v;
                    }
                }
                """
                .replace("{{pkg}}", pkg)
                .replace("{{imports}}", String.join("\n", imports))
                .replace("{{componentes}}", String.join(",\n", componentes))
                .replace("{{deEntidad}}", deEntidad)
                .replace("{{aEntidad}}", aEntidad)
                .replace("{{nombre}}", nombre);
    }

    /** @Inheritance y compania, segun la estrategia y el lugar en la jerarquia (Regla 4). */
    private static String anotacionesDeHerencia(Herencia h) {
        StringBuilder sb = new StringBuilder();
        if (h.tablaUnica()) {
            if (h.raiz()) {
                sb.append("@Inheritance(strategy = InheritanceType.SINGLE_TABLE)\n")
                  .append("@DiscriminatorColumn(name = \"").append(h.discriminador())
                  .append("\", length = ").append(h.largoDiscriminador()).append(")\n");
            }
            // Una clase abstracta no tiene filas propias: no lleva valor.
            if (h.valor() != null) {
                sb.append("@DiscriminatorValue(\"").append(h.valor()).append("\")\n");
            }
        } else if (h.raiz()) {
            sb.append("@Inheritance(strategy = InheritanceType.JOINED)\n");
        } else if (h.madre() != null) {
            sb.append("@PrimaryKeyJoinColumn(name = \"id\")\n");
        }
        return sb.toString();
    }

    /**
     * Version y auditoria de la raiz (ADR-034). `@Version` hace que dos
     * escrituras concurrentes sobre la misma fila no se pisen: la segunda
     * falla y el manejador de errores responde 409. Las fechas las pone la
     * propia entidad al guardarse, sin depender de quien la llame.
     */
    private static String auditoriaJpa(Meta m) {
        return """
                    @Version
                    @Column(name = "{{cv}}", nullable = false)
                    private Long {{v}};

                    @Column(name = "{{cc}}", nullable = false, updatable = false)
                    private LocalDateTime {{c}};

                    @Column(name = "{{ca}}", nullable = false)
                    private LocalDateTime {{a}};

                    @PrePersist
                    void alCrear() {
                        LocalDateTime ahora = LocalDateTime.now();
                        {{c}} = ahora;
                        {{a}} = ahora;
                    }

                    @PreUpdate
                    void alActualizar() {
                        {{a}} = LocalDateTime.now();
                    }

                    public Long {{gv}}() { return {{v}}; }
                    public LocalDateTime {{gc}}() { return {{c}}; }
                    public LocalDateTime {{ga}}() { return {{a}}; }

                """
                .replace("{{cv}}", m.columnaVersion()).replace("{{cc}}", m.columnaCreado())
                .replace("{{ca}}", m.columnaActualizado())
                .replace("{{gv}}", NombresJava.getter(m.version()))
                .replace("{{gc}}", NombresJava.getter(m.creado()))
                .replace("{{ga}}", NombresJava.getter(m.actualizado()))
                .replace("{{v}}", m.version()).replace("{{c}}", m.creado()).replace("{{a}}", m.actualizado());
    }

    private static String accesores(String tipo, String campo) {
        return "    public " + tipo + " " + NombresJava.getter(campo) + "() { return " + campo + "; }\n"
                + "    public void " + NombresJava.setter(campo) + "(" + tipo + " " + campo
                + ") { this." + campo + " = " + campo + "; }\n";
    }

    private static String entidadTexto(String pkg, String clase, String tabla, List<Campo> campos,
                                       String relaciones, String accesoresRel, boolean conListas,
                                       Herencia herencia, Meta meta) {
        StringBuilder cuerpo = new StringBuilder();
        for (Campo c : campos) {
            if (c.esId()) {
                cuerpo.append("""
                            @Id
                            @GeneratedValue(strategy = GenerationType.UUID)
                            @Column(name = "id", nullable = false, updatable = false)
                            private UUID id;

                        """);
                continue;
            }
            String largo = c.largo() != null && ("String".equals(c.tipoJava()) || c.enumerado())
                    ? ", length = " + c.largo() : "";
            // STRING y no ORDINAL: con ORDINAL la base guarda la posicion, y
            // reordenar los literales cambia el significado de lo ya guardado.
            if (c.enumerado()) cuerpo.append("    @Enumerated(EnumType.STRING)\n");
            cuerpo.append("    @Column(name = \"").append(c.columna()).append("\", nullable = ")
                    .append(c.nullable()).append(largo).append(")\n")
                    .append("    private ").append(c.tipoJava()).append(" ").append(c.nombreJava())
                    .append(";\n\n");
        }

        StringBuilder accesores = new StringBuilder();
        for (Campo c : campos) {
            accesores.append("    public ").append(c.tipoJava()).append(" ")
                    .append(NombresJava.getter(c.nombreJava())).append("() { return ")
                    .append(c.nombreJava()).append("; }\n")
                    .append("    public void ").append(NombresJava.setter(c.nombreJava())).append("(")
                    .append(c.tipoJava()).append(" ").append(c.nombreJava()).append(") { this.")
                    .append(c.nombreJava()).append(" = ").append(c.nombreJava()).append("; }\n");
        }

        return """
                package {{pkg}}.domain;

                import jakarta.persistence.*;
                import java.util.UUID;
                {{importsLista}}{{imports}}

                @Entity
                {{anotacionTabla}}{{herencia}}public {{abstracta}}class {{clase}}{{extends}} {

                {{campos}}{{relaciones}}{{auditoria}}{{accesores}}{{accesoresRel}}{{igualdad}}}
                """
                // ADR-034: solo la raiz declara version y auditoria; una hija
                // las hereda, igual que hereda el id.
                .replace("{{auditoria}}", herencia.madre() != null ? "" : auditoriaJpa(meta))
                // Una subclase SINGLE_TABLE no declara tabla: vive en la de su raiz.
                .replace("{{anotacionTabla}}", herencia.tablaUnica() && herencia.madre() != null
                        ? "" : "@Table(name = \"" + tabla + "\")\n")
                .replace("{{herencia}}", anotacionesDeHerencia(herencia))
                .replace("{{abstracta}}", herencia.abstracta() ? "abstract " : "")
                .replace("{{extends}}", herencia.madre() != null ? " extends " + herencia.madre() : "")
                // La hija hereda equals/hashCode de la raiz: redefinirlos con su
                // propia clase romperia la igualdad entre un Empleado y la
                // Persona que es el mismo registro.
                .replace("{{igualdad}}", herencia.madre() != null ? "" : """

                            // REGLA 3: equals/hashCode SOLO por id. Con los demas campos, una
                            // entidad cambia de identidad al editarla y rompe los Set de JPA.
                            @Override
                            public boolean equals(Object o) {
                                if (this == o) return true;
                                if (!(o instanceof {{clase}} otro)) return false;
                                return id != null && id.equals(otro.id);
                            }

                            @Override
                            public int hashCode() { return {{clase}}.class.hashCode(); }
                        """)
                .replace("{{pkg}}", pkg)
                .replace("{{importsLista}}", (conListas ? "import java.util.ArrayList;\nimport java.util.List;\n" : "")
                        + (herencia.madre() == null && !importsDe(campos).contains("java.time.LocalDateTime;")
                                ? "import java.time.LocalDateTime;\n" : ""))
                .replace("{{imports}}", importsDe(campos))
                .replace("{{tabla}}", tabla)
                .replace("{{campos}}", cuerpo.toString())
                .replace("{{relaciones}}", relaciones)
                .replace("{{accesores}}", accesores.toString())
                .replace("{{accesoresRel}}", accesoresRel)
                .replace("{{clase}}", clase);
    }

    private static String repositorio(String pkg, String clase, List<Asociacion> asociaciones) {
        if (asociaciones.isEmpty()) {
            return """
                    package {{pkg}}.repository;

                    import {{pkg}}.domain.{{clase}};
                    import org.springframework.data.jpa.repository.JpaRepository;

                    import java.util.UUID;

                    public interface {{clase}}Repository extends JpaRepository<{{clase}}, UUID> {
                    }
                    """.replace("{{pkg}}", pkg).replace("{{clase}}", clase);
        }

        String rutas = asociaciones.stream()
                .map(a -> "\"" + a.campo() + "\"")
                .collect(Collectors.joining(", "));

        // Un buscador por cada FK. Lo que hace falta para que "los pedidos de
        // un cliente" sea una consulta y no traerse la tabla entera. Con
        // orden (todo) y con pagina (ADR-034).
        StringBuilder porFk = new StringBuilder();
        for (Asociacion a : asociaciones) {
            String metodo = "findBy" + mayuscula(a.campo()) + "_Id(UUID " + a.campo() + "Id";
            porFk.append("\n    /** El lado inverso de ").append(a.campo())
                    .append(": lo que `inverseOf` promete en app-manifest.json. */\n")
                    .append("    @EntityGraph(attributePaths = { ").append(rutas).append(" })\n")
                    .append("    List<").append(clase).append("> ").append(metodo).append(", Sort orden);\n\n")
                    .append("    @EntityGraph(attributePaths = { ").append(rutas).append(" })\n")
                    .append("    Page<").append(clase).append("> ").append(metodo).append(", Pageable pagina);\n");
        }

        return """
                package {{pkg}}.repository;

                import {{pkg}}.domain.{{clase}};
                import org.springframework.data.domain.Page;
                import org.springframework.data.domain.Pageable;
                import org.springframework.data.domain.Sort;
                import org.springframework.data.jpa.repository.EntityGraph;
                import org.springframework.data.jpa.repository.JpaRepository;

                import java.util.List;
                import java.util.UUID;

                public interface {{clase}}Repository extends JpaRepository<{{clase}}, UUID> {

                    /**
                     * REGLA 7: las asociaciones son LAZY, asi que un listado que las
                     * toque dispararia una consulta por fila. El @EntityGraph las trae
                     * en la misma. Es el anti N+1.
                     */
                    @Override
                    @EntityGraph(attributePaths = { {{rutas}} })
                    List<{{clase}}> findAll();

                    @Override
                    @EntityGraph(attributePaths = { {{rutas}} })
                    List<{{clase}}> findAll(Sort orden);

                    @Override
                    @EntityGraph(attributePaths = { {{rutas}} })
                    Page<{{clase}}> findAll(Pageable pagina);
                {{porFk}}}
                """.replace("{{pkg}}", pkg).replace("{{rutas}}", rutas)
                .replace("{{porFk}}", porFk.toString()).replace("{{clase}}", clase);
    }

    /** Primera letra en mayuscula, para armar `findByCliente_Id` desde `cliente`. */
    private static String mayuscula(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /**
     * Los campos por los que el listado acepta `sort`: los propios de la
     * entidad (no las asociaciones ni las colecciones) y los de auditoria.
     * Un nombre que no esta aca es un 400, no un 500 de Spring Data.
     */
    static String ordenables(List<Campo> campos, Meta meta) {
        List<String> nombres = new ArrayList<>();
        for (Campo c : campos) nombres.add("\"" + c.nombreJava() + "\"");
        nombres.add("\"" + meta.creado() + "\"");
        nombres.add("\"" + meta.actualizado() + "\"");
        return "Set.of(" + String.join(", ", nombres) + ")";
    }

    private static String servicio(String pkg, String clase, List<Campo> campos,
                                   List<Asociacion> asociaciones, List<ColeccionDeValores> valores,
                                   List<ModeloLogico.Incrustado> incrustados,
                                   Stubs stubs, boolean instanciable, Meta meta,
                                   Map<String, String> referencias) {
        StringBuilder copiar = new StringBuilder();
        for (Campo c : campos) {
            if (c.esId()) continue;
            copiar.append("        entidad.").append(NombresJava.setter(c.nombreJava()))
                    .append("(dto.").append(c.nombreJava()).append("());\n");
        }
        // El DTO trae ids, no entidades: getReferenceById arma la referencia
        // sin traer la fila entera de la base.
        for (Asociacion a : asociaciones) {
            String campoId = a.campo() + "Id";
            // Apuntar a un id que no existe es un 409 que dice cual, no la
            // violacion de FK generica que daria la base al hacer flush.
            copiar.append("        if (dto.").append(campoId).append("() != null && !")
                    .append(repositorioDe(clase, a.claseDestino())).append(".existsById(dto.").append(campoId)
                    .append("())) {\n")
                    .append("            throw new ConflictoException(\"No existe ").append(a.claseDestino())
                    .append(" \" + dto.").append(campoId).append("() + \" (").append(campoId).append(")\");\n")
                    .append("        }\n");
            copiar.append("        entidad.").append(NombresJava.setter(a.campo()))
                    .append("(dto.").append(campoId).append("() == null ? null : ")
                    .append(repositorioDe(clase, a.claseDestino())).append(".getReferenceById(dto.")
                    .append(campoId).append("()));\n");
        }
        for (ColeccionDeValores v : valores) {
            copiar.append("        entidad.").append(NombresJava.setter(v.campo()))
                    .append("(dto.").append(v.campo()).append("() == null ? new ArrayList<>() : new ArrayList<>(dto.")
                    .append(v.campo()).append("()));\n");
        }

        for (ModeloLogico.Incrustado i : incrustados) {
            String campo = NombresJava.campo(i.campo());
            copiar.append("        entidad.").append(NombresJava.setter(campo))
                    .append("(dto.").append(campo).append("() == null ? null : dto.").append(campo)
                    .append("().aEntidad());\n");
        }

        // Mismo orden que los componentes del record en dto(): campos,
        // asociaciones, colecciones de valores, valores embebidos.
        List<String> aDto = new ArrayList<>();
        for (Campo c : campos) aDto.add("e." + NombresJava.getter(c.nombreJava()) + "()");
        for (Asociacion a : asociaciones) {
            String getter = "e." + NombresJava.getter(a.campo()) + "()";
            aDto.add(getter + " == null ? null : " + getter + ".getId()");
        }
        for (ColeccionDeValores v : valores) {
            aDto.add("List.copyOf(e." + NombresJava.getter(v.campo()) + "())");
        }
        for (ModeloLogico.Incrustado i : incrustados) {
            String getter = "e." + NombresJava.getter(NombresJava.campo(i.campo())) + "()";
            aDto.add(getter + " == null ? null : " + NombresJava.clase(i.clase()) + "Dto.de(" + getter + ")");
        }
        aDto.add("e." + NombresJava.getter(meta.version()) + "()");
        aDto.add("e." + NombresJava.getter(meta.creado()) + "()");
        aDto.add("e." + NombresJava.getter(meta.actualizado()) + "()");

        // Quien referencia a esta entidad, por nombre de restriccion: asi el
        // 409 del borrado dice que la sostiene.
        String mapaReferencias = referencias.isEmpty() ? "Map.of()" : "Map.ofEntries(\n"
                + referencias.entrySet().stream()
                        .map(e -> "            Map.entry(\"" + e.getKey() + "\", \"" + e.getValue() + "\")")
                        .collect(Collectors.joining(",\n"))
                + ")";

        StringBuilder deps = new StringBuilder();
        StringBuilder params = new StringBuilder();
        StringBuilder asigna = new StringBuilder();
        StringBuilder importsRel = new StringBuilder();
        // Por clase destino, no por asociacion (ver repositorioDe), y sin la
        // propia clase: esa ya entra por `repositorio`. LinkedHashSet para que
        // el constructor generado no cambie de firma entre dos generaciones del
        // mismo modelo.
        Set<String> destinos = new LinkedHashSet<>(asociaciones.stream().map(Asociacion::claseDestino).toList());
        destinos.remove(clase);
        for (String destino : destinos) {
            String repo = repositorioDe(clase, destino);
            deps.append("    private final ").append(destino).append("Repository ")
                    .append(repo).append(";\n");
            params.append(", ").append(destino).append("Repository ").append(repo);
            asigna.append("        this.").append(repo).append(" = ").append(repo).append(";\n");
            importsRel.append("import ").append(pkg).append(".repository.")
                    .append(destino).append("Repository;\n");
        }
        new TreeSet<>(incrustados.stream().map(i -> NombresJava.clase(i.clase())).toList())
                .forEach(v -> importsRel.append("import ").append(pkg).append(".web.dto.").append(v)
                        .append("Dto;\n"));
        // Los tipos de los stubs pueden traer imports que la plantilla no tiene.
        for (String imp : stubs.imports()) {
            importsRel.append("import ").append(imp).append(";\n");
        }

        return """
                package {{pkg}}.service;

                import {{pkg}}.domain.{{clase}};
                import {{pkg}}.repository.{{clase}}Repository;
                {{importsRel}}import {{pkg}}.web.dto.{{clase}}Dto;
                import org.springframework.dao.DataIntegrityViolationException;
                import org.springframework.data.domain.Page;
                import org.springframework.data.domain.Sort;
                import org.springframework.stereotype.Service;
                import org.springframework.transaction.annotation.Transactional;

                {{importArrayList}}import java.util.List;
                import java.util.Locale;
                import java.util.Map;
                import java.util.NoSuchElementException;
                import java.util.Set;
                import java.util.UUID;

                /**
                 * REGLA 6: en 5 capas el comportamiento vive aca, no en la Entity.
                 * Los metodos del UML aterrizan en esta clase como stubs.
                 *
                 * Hacia afuera solo salen DTOs, y el mapeo se hace ACA, dentro de la
                 * transaccion. Si el controlador devolviera la entidad, Jackson la
                 * serializaria con la sesion ya cerrada: toda relacion LAZY explota
                 * y las bidireccionales entran en recursion infinita.
                 */
                @Service
                public class {{clase}}Service {

                    /** Por que campos se puede ordenar el listado (`sort=campo,asc|desc`). */
                    static final Set<String> ORDENABLES = {{ordenables}};

                    /** Restriccion de clave foranea -> quien la sostiene, para el 409 del borrado. */
                    private static final Map<String, String> REFERENCIAS = {{referencias}};

                    private final {{clase}}Repository repositorio;
                {{deps}}
                    public {{clase}}Service({{clase}}Repository repositorio{{params}}) {
                        this.repositorio = repositorio;
                {{asigna}}    }

                    /**
                     * Sin `page` devuelve todo (la app movil lista asi); con `page`,
                     * esa pagina. En los dos casos el total viaja aparte, en la
                     * cabecera X-Total-Count.
                     */
                    @Transactional(readOnly = true)
                    public Pagina<{{clase}}Dto> listar(Integer page, Integer size, String sort) {
                        Sort orden = Paginas.orden(sort, ORDENABLES);
                        if (page == null) {
                            List<{{clase}}Dto> todos = repositorio.findAll(orden).stream().map({{clase}}Service::aDto).toList();
                            return new Pagina<>(todos, todos.size());
                        }
                        Page<{{clase}}> pagina = repositorio.findAll(Paginas.pagina(page, size, orden));
                        return new Pagina<>(pagina.getContent().stream().map({{clase}}Service::aDto).toList(),
                                pagina.getTotalElements());
                    }
                {{listarPorFk}}
                    @Transactional(readOnly = true)
                    public {{clase}}Dto obtener(UUID id) {
                        return aDto(buscar(id));
                    }

                {{escritura}}
                    @Transactional
                    public void eliminar(UUID id) {
                        if (!repositorio.existsById(id)) {
                            throw new NoSuchElementException("No existe {{clase}} " + id);
                        }
                        try {
                            repositorio.deleteById(id);
                            // Flush aca y no al commit: si una FK impide el borrado, la
                            // excepcion sale aca y se traduce a un 409 que dice por que.
                            repositorio.flush();
                        } catch (DataIntegrityViolationException e) {
                            throw new ConflictoException("No se puede eliminar {{clase}} " + id + ": "
                                    + quienReferencia(e) + " todavia lo referencia. Elimina o reasigna eso primero.");
                        }
                    }

                    private static String quienReferencia(DataIntegrityViolationException e) {
                        String detalle = String.valueOf(e.getMostSpecificCause().getMessage()).toLowerCase(Locale.ROOT);
                        return REFERENCIAS.entrySet().stream().filter(r -> detalle.contains(r.getKey()))
                                .map(Map.Entry::getValue).findFirst().orElse("otro registro");
                    }

                    private {{clase}} buscar(UUID id) {
                        return repositorio.findById(id)
                                .orElseThrow(() -> new NoSuchElementException("No existe {{clase}} " + id));
                    }

                {{copiarMetodo}}
                    static {{clase}}Dto aDto({{clase}} e) {
                        return new {{clase}}Dto(
                                {{aDto}});
                    }
                {{stubs}}}
                """
                .replace("{{stubs}}", stubs.codigo())
                .replace("{{pkg}}", pkg)
                .replace("{{importsRel}}", importsRel.toString())
                .replace("{{importArrayList}}", valores.isEmpty() ? "" : "import java.util.ArrayList;\n")
                .replace("{{deps}}", deps.toString())
                .replace("{{params}}", params.toString())
                .replace("{{asigna}}", asigna.toString())
                .replace("{{listarPorFk}}", listarPorFk(clase, asociaciones))
                .replace("{{ordenables}}", ordenables(campos, meta))
                .replace("{{referencias}}", mapaReferencias)
                // Una clase abstracta no se instancia: su API lista, obtiene y
                // elimina (polimorficamente), y se crea a traves de sus hijas.
                .replace("{{escritura}}", !instanciable ? "" : """
                            @Transactional
                            public {{clase}}Dto crear({{clase}}Dto dto) {
                                {{clase}} entidad = new {{clase}}();
                                copiar(dto, entidad);
                                return aDto(repositorio.saveAndFlush(entidad));
                            }

                            /**
                             * Reemplazo completo. Si el cliente manda la version que leyo y
                             * ya no es la actual, alguien escribio en el medio: 409 en vez
                             * de pisarle el cambio sin avisar.
                             */
                            @Transactional
                            public {{clase}}Dto actualizar(UUID id, {{clase}}Dto dto) {
                                {{clase}} entidad = buscar(id);
                                Versiones.comprobar(dto.{{version}}(), entidad.{{getVersion}}(), "{{clase}}", id);
                                copiar(dto, entidad);
                                return aDto(repositorio.saveAndFlush(entidad));
                            }

                        """)
                .replace("{{version}}", meta.version())
                .replace("{{getVersion}}", NombresJava.getter(meta.version()))
                .replace("{{copiarMetodo}}", !instanciable ? "" : """
                            private void copiar({{clase}}Dto dto, {{clase}} entidad) {
                        {{copiar}}    }

                        """)
                .replace("{{copiar}}", copiar.toString())
                .replace("{{aDto}}", String.join(",\n                ", aDto))
                .replace("{{clase}}", clase);
    }

    /**
     * Un `listarPorX(UUID)` por cada FK.
     *
     * Existe porque el manifiesto ya lo prometia: `inverseOf` dice que "los
     * pedidos de un cliente" es el listado de Pedido filtrado por `clienteId`.
     * Hasta ahora esa promesa no tenia con que cumplirse y el cliente tenia
     * que bajarse la tabla entera y filtrar en memoria.
     */
    private static String listarPorFk(String clase, List<Asociacion> asociaciones) {
        StringBuilder salida = new StringBuilder();
        for (Asociacion a : asociaciones) {
            String id = a.campo() + "Id";
            String buscar = "repositorio.findBy" + mayuscula(a.campo()) + "_Id(" + id;
            salida.append("""

                        @Transactional(readOnly = true)
                        public Pagina<{{clase}}Dto> listarPor{{Campo}}(UUID {{id}}, Integer page, Integer size, String sort) {
                            Sort orden = Paginas.orden(sort, ORDENABLES);
                            if (page == null) {
                                List<{{clase}}Dto> todos = {{buscar}}, orden).stream().map({{clase}}Service::aDto).toList();
                                return new Pagina<>(todos, todos.size());
                            }
                            Page<{{clase}}> pagina = {{buscar}}, Paginas.pagina(page, size, orden));
                            return new Pagina<>(pagina.getContent().stream().map({{clase}}Service::aDto).toList(),
                                    pagina.getTotalElements());
                        }
                    """
                    .replace("{{Campo}}", mayuscula(a.campo()))
                    .replace("{{id}}", id)
                    .replace("{{buscar}}", buscar)
                    .replace("{{clase}}", clase));
        }
        return salida.toString();
    }

    /** El codigo de los stubs y los imports que hacen falta para compilarlos. */
    record Stubs(String codigo, List<String> imports) {
        static final Stubs NINGUNO = new Stubs("", List.of());
    }

    /**
     * Los metodos del UML como stubs del Service (REGLA 6).
     *
     * El generador escribe la FIRMA, no el cuerpo: inventar comportamiento
     * seria pasar de traducir un modelo a adivinarlo. El
     * `UnsupportedOperationException` es deliberado: si alguien despliega sin
     * completarlo, falla ruidosamente en vez de devolver un null silencioso.
     */
    private static Stubs stubsDe(String nombreUml, String clase, List<UmlMethod> metodos,
                                 List<String> advertencias, Map<String, String> tiposEnum,
                                 String pkg) {
        if (metodos.isEmpty()) return Stubs.NINGUNO;

        StringBuilder sb = new StringBuilder();
        Set<String> imports = new TreeSet<>();
        ObjectMapper mapper = new ObjectMapper();

        for (UmlMethod m : metodos) {
            String nombre = NombresJava.campo(m.getName());
            List<String> tipos = new ArrayList<>();
            List<String> nombres = new ArrayList<>();
            try {
                for (JsonNode par : mapper.readTree(m.getParams() == null ? "[]" : m.getParams())) {
                    tipos.add(tipoJavaDe(par.path("type").asText("string"), tiposEnum));
                    nombres.add(NombresJava.campo(par.path("name").asText("param")));
                }
            } catch (JsonProcessingException e) {
                advertencias.add("El metodo " + nombreUml + "." + m.getName()
                        + " tiene parametros ilegibles: se genero sin ellos (Regla 10).");
            }

            // El Service ya trae su CRUD. Un metodo del UML con la misma firma
            // no compila, asi que se avisa y se deja afuera: el criterio del
            // hito es que el proyecto generado compile.
            String firma = nombre + "(" + String.join(", ", tipos) + ")";
            if (CRUD_DEL_SERVICE.contains(nombre) && tipos.isEmpty()) {
                advertencias.add("El metodo " + nombreUml + "." + m.getName() + "() choca con la"
                        + " operacion CRUD " + nombre + "() que el Service ya genera:"
                        + " no se emitio el stub. Renombralo en el modelo (Regla 10).");
                continue;
            }

            String retorno = "void".equalsIgnoreCase(m.getReturnType())
                    ? "void" : tipoJavaDe(m.getReturnType(), tiposEnum);
            agregarImport(imports, retorno);
            tipos.forEach(t -> agregarImport(imports, t));
            // Un enum del modelo vive en el paquete domain, no en java.*.
            for (String t : tipos) if (tiposEnum.containsValue(t)) imports.add(pkg + ".domain." + t);
            if (tiposEnum.containsValue(retorno)) imports.add(pkg + ".domain." + retorno);

            StringBuilder args = new StringBuilder();
            for (int i = 0; i < tipos.size(); i++) {
                if (i > 0) args.append(", ");
                args.append(tipos.get(i)).append(' ').append(nombres.get(i));
            }

            sb.append("""

                        /** REGLA 6: firma tomada del UML ({{uml}}). El cuerpo lo escribe el desarrollador. */
                        {{vis}}{{ret}} {{nombre}}({{args}}) {
                            throw new UnsupportedOperationException("{{firma}}: declarado en el UML, sin implementar");
                        }
                    """
                    .replace("{{uml}}", m.getVisibility().simbolo() + " " + m.getName()
                            + "(" + String.join(", ", tipos) + "): " + retorno)
                    .replace("{{vis}}", palabraDe(m.getVisibility()))
                    .replace("{{ret}}", retorno)
                    .replace("{{nombre}}", nombre)
                    .replace("{{args}}", args.toString())
                    .replace("{{firma}}", firma));
        }
        return new Stubs(sb.toString(), List.copyOf(imports));
    }

    /** Lo que el Service ya define por su cuenta. */
    private static final Set<String> CRUD_DEL_SERVICE =
            Set.of("listar", "obtener", "crear", "actualizar", "eliminar");

    /** El tipo Java de un tipo UML: primero las enumeraciones del modelo, despues la tabla de tipos. */
    private static String tipoJavaDe(String tipoUml, Map<String, String> tiposEnum) {
        String enumerado = tiposEnum.get(tipoUml);
        return enumerado != null ? enumerado : TipoJava.de(tipoUml);
    }

    private static void agregarImport(Set<String> imports, String tipoJava) {
        String imp = TipoJava.importDe(tipoJava);
        // java.util.UUID y java.util.List ya los trae la plantilla del Service:
        // repetirlos no rompe, pero codigo generado descuidado se nota.
        if (imp != null && !imp.equals("java.util.UUID")) imports.add(imp);
    }

    private static String palabraDe(Visibility v) {
        return switch (v) {
            case PUBLIC -> "public ";
            case PRIVATE -> "private ";
            case PROTECTED -> "protected ";
            case PACKAGE -> "";          // sin modificador: visibilidad de paquete
        };
    }

    private static String dto(String pkg, String clase, List<Campo> campos,
                              List<Asociacion> asociaciones, List<ColeccionDeValores> valores,
                              List<ModeloLogico.Incrustado> incrustados, Meta meta) {
        List<String> componentes = new ArrayList<>();
        boolean necesitaValidacion = false;
        boolean necesitaRango = false;
        for (Campo c : campos) {
            StringBuilder comp = new StringBuilder("        ");
            if ((!c.nullable() || c.obligatorio()) && !c.esId()) {
                comp.append("@NotNull ");
                necesitaValidacion = true;
            }
            if (c.largo() != null && "String".equals(c.tipoJava())) {
                comp.append("@Size(max = ").append(c.largo()).append(") ");
                necesitaValidacion = true;
            }
            // REGLA 9: el rango se valida en el DTO y en la base (CHECK). Enteros
            // con @Min/@Max, que solo aceptan long; decimales con @DecimalMin.
            if (c.rango() != null) {
                ModeloLogico.Rango r = c.rango();
                if (r.minimo() != null) {
                    comp.append(r.entero() ? "@Min(" + ModeloLogico.Rango.texto(r.minimo()) + ") "
                            : "@DecimalMin(\"" + ModeloLogico.Rango.texto(r.minimo()) + "\") ");
                }
                if (r.maximo() != null) {
                    comp.append(r.entero() ? "@Max(" + ModeloLogico.Rango.texto(r.maximo()) + ") "
                            : "@DecimalMax(\"" + ModeloLogico.Rango.texto(r.maximo()) + "\") ");
                }
                necesitaRango = true;
            }
            comp.append(c.tipoJava()).append(" ").append(c.nombreJava());
            componentes.add(comp.toString());
        }

        // Las asociaciones viajan como ID, no como entidad anidada: un DTO que
        // arrastra entidades termina serializando medio grafo.
        for (Asociacion a : asociaciones) {
            StringBuilder comp = new StringBuilder("        ");
            if (!a.nullable()) {
                comp.append("@NotNull ");
                necesitaValidacion = true;
            }
            comp.append("UUID ").append(a.campo()).append("Id");
            componentes.add(comp.toString());
        }

        // A-3 leido como OO: la coleccion de valores viaja entera. Sin esto el
        // @ElementCollection existia en la Entity pero no se podia cargar ni
        // leer por la API.
        Set<String> importsExtra = new TreeSet<>();
        for (ColeccionDeValores v : valores) {
            componentes.add("        List<" + v.tipoElemento() + "> " + v.campo());
            importsExtra.add("import java.util.List;");
            String imp = TipoJava.importDe(v.tipoElemento());
            if (imp != null && !imp.equals("java.util.UUID")) importsExtra.add("import " + imp + ";");
        }
        // REGLA 5: el valor viaja anidado y se valida por dentro (@Valid).
        for (ModeloLogico.Incrustado i : incrustados) {
            componentes.add("        @Valid " + (i.obligatorio() ? "@NotNull " : "")
                    + NombresJava.clase(i.clase()) + "Dto " + NombresJava.campo(i.campo()));
            importsExtra.add("import jakarta.validation.Valid;");
            if (i.obligatorio()) necesitaValidacion = true;
        }
        for (Campo c : campos) {
            if (c.enumerado()) importsExtra.add("import " + pkg + ".domain." + c.tipoJava() + ";");
        }
        for (ColeccionDeValores v : valores) {
            if (v.enumerado()) importsExtra.add("import " + pkg + ".domain." + v.tipoElemento() + ";");
        }
        // ADR-034: la version y la auditoria SALEN siempre; al entrar se
        // ignoran, salvo la version, que si viene se compara con la actual.
        componentes.add("        Long " + meta.version());
        componentes.add("        LocalDateTime " + meta.creado());
        componentes.add("        LocalDateTime " + meta.actualizado());
        importsExtra.add("import java.time.LocalDateTime;");
        String imports = importsDe(campos);
        for (String imp : importsExtra) {
            if (!imports.contains(imp)) imports = imports.isEmpty() ? imp : imports + "\n" + imp;
        }

        String importsValidacion = (necesitaValidacion
                ? "import jakarta.validation.constraints.NotNull;\nimport jakarta.validation.constraints.Size;\n"
                : "")
                + (necesitaRango
                ? "import jakarta.validation.constraints.DecimalMax;\nimport jakarta.validation.constraints.DecimalMin;\n"
                  + "import jakarta.validation.constraints.Max;\nimport jakarta.validation.constraints.Min;\n"
                : "");

        return """
                package {{pkg}}.web.dto;

                {{importsValidacion}}import java.util.UUID;
                {{imports}}

                /**
                 * REGLA 9: la validacion empieza aca y se repite en la Entity y en la BD.
                 *
                 * Es el mismo record para entrar y para salir: el controlador nunca
                 * recibe ni devuelve una entidad.
                 */
                public record {{clase}}Dto(
                {{componentes}}
                ) {
                }
                """
                .replace("{{pkg}}", pkg)
                .replace("{{importsValidacion}}", importsValidacion)
                .replace("{{imports}}", imports)
                .replace("{{componentes}}", String.join(",\n", componentes))
                .replace("{{clase}}", clase);
    }

    private static String enumeracion(String pkg, String nombre, ModeloLogico.Enumeracion e) {
        return """
                package {{pkg}}.domain;

                /**
                 * Enumeracion del modelo conceptual.
                 *
                 * Se guarda por NOMBRE (@Enumerated(EnumType.STRING)) y la base la
                 * restringe con un CHECK sobre estos mismos literales.
                 */
                public enum {{nombre}} {
                {{literales}}
                }
                """
                .replace("{{pkg}}", pkg)
                .replace("{{nombre}}", nombre)
                .replace("{{literales}}", e.literales().stream()
                        .map(l -> "    " + l).collect(Collectors.joining(",\n")));
    }

    private static String readme(Opciones o, int entidades, List<String> advertencias, Meta meta) {
        return PlantillasProduccion.readme(o, entidades, advertencias, meta);
    }
}
