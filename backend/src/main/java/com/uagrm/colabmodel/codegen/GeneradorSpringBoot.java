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

    public record Opciones(String nombreProyecto, String paquete) {
        public static Opciones por(String nombreProyecto) {
            return new Opciones(nombreProyecto, "com.uagrm.generado");
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
        ModeloLogico logico = DerivadorLogico.derivar(clases, atributos, relaciones, decisiones);
        Map<String, String> archivos = new LinkedHashMap<>();
        List<String> advertencias = new ArrayList<>(logico.advertencias());

        String pkg = opciones.paquete();
        String ruta = "src/main/java/" + pkg.replace('.', '/');

        archivos.put("pom.xml", pom(opciones));
        archivos.put(ruta + "/GeneratedApplication.java", aplicacion(pkg));
        archivos.put(ruta + "/web/ManejadorDeErrores.java", manejadorDeErrores(pkg));
        archivos.put(ruta + "/config/CorsConfig.java", cors(pkg));
        archivos.put("src/main/resources/static/index.html", consola());
        archivos.put("src/main/resources/application.yml", configuracion(opciones));
        archivos.put(RUTA_DDL, ddl(logico));

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
                archivos.put(ruta + "/domain/" + nombreValor + ".java", valorEmbebido(pkg, nombreValor, camposValor));
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

            archivos.put(ruta + "/domain/" + nombreJava + ".java",
                    entidad(pkg, nombreJava, tabla, propios, asociaciones, colecciones,
                            valoresPorClase.getOrDefault(clase.getId(), List.of()), herencia,
                            incrustadosPropios));
            archivos.put(ruta + "/repository/" + nombreJava + "Repository.java",
                    repositorio(pkg, nombreJava, asociacionesCompletas));
            archivos.put(ruta + "/service/" + nombreJava + "Service.java",
                    servicio(pkg, nombreJava, campos, asociacionesCompletas, valores, incrustadosCompletos,
                            stubsDe(clase.getName(), nombreJava,
                                    metodosPorClase.getOrDefault(clase.getId(), List.of()),
                                    advertencias, tiposEnum, pkg),
                            !herencia.abstracta()));
            archivos.put(ruta + "/web/dto/" + nombreJava + "Dto.java",
                    dto(pkg, nombreJava, campos, asociacionesCompletas, valores, incrustadosCompletos));
            archivos.put(ruta + "/web/" + nombreJava + "Controller.java",
                    controlador(pkg, nombreJava, tabla, !herencia.abstracta(), asociacionesCompletas));
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

            archivos.put(ruta + "/domain/" + nombreJava + ".java",
                    entidad(pkg, nombreJava, tabla, campos, asociaciones,
                            coleccionesDe(nombreJava, logico, asociacionesPorTabla, porRelacion,
                                    claseDeTabla),
                            List.of(), Herencia.NINGUNA, List.of()));
            archivos.put(ruta + "/repository/" + nombreJava + "Repository.java",
                    repositorio(pkg, nombreJava, asociaciones));
            // Sin metodos: una clase asociativa no sale de ninguna clase UML,
            // asi que no hay firmas del modelo que aterrizar (Regla 6).
            archivos.put(ruta + "/service/" + nombreJava + "Service.java",
                    servicio(pkg, nombreJava, campos, asociaciones, List.of(), List.of(), Stubs.NINGUNO, true));
            archivos.put(ruta + "/web/dto/" + nombreJava + "Dto.java",
                    dto(pkg, nombreJava, campos, asociaciones, List.of(), List.of()));
            archivos.put(ruta + "/web/" + nombreJava + "Controller.java",
                    controlador(pkg, nombreJava, tabla, true, asociaciones));
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

        archivos.put(ManifestDeApp.RUTA, ManifestDeApp.json(opciones.nombreProyecto(), manifiesto));
        String ejemplo = DatosDeEjemplo.sql(logico, conEntity);
        if (ejemplo != null) archivos.put(DatosDeEjemplo.RUTA, ejemplo);
        archivos.put("README.md", readme(opciones, entidades, advertencias));
        return new ProyectoGenerado(opciones.nombreProyecto(), archivos, advertencias);
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

    private record Campo(String nombreJava, String columna, String tipoJava, boolean nullable,
                         boolean esId, Integer largo, boolean enumerado, boolean obligatorio,
                         ModeloLogico.Rango rango, boolean unico) {
        Campo comoObligatorio() {
            return new Campo(nombreJava, columna, tipoJava, nullable, esId, largo, enumerado, true, rango, unico);
        }
    }

    /** REGLA 7: el lado que tiene la FK. `@ManyToOne` (o `@OneToOne` en 1:1). */
    private record Asociacion(String campo, String claseDestino, String columna,
                              boolean nullable, boolean unoAUno) { }

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
    private record Herencia(String madre, boolean raiz, boolean abstracta,
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
    private record ColeccionDeValores(String campo, String tipoElemento, String tabla,
                                      String joinColumn, String columna, boolean enumerado) { }

    /** El lado inverso: la coleccion, con el cascade que pide el tipo de relacion. */
    private record Coleccion(String campo, String claseElemento, String mappedBy,
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
                    c.nullable(), c.unique()));
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
    private static String campoDeFk(String columna) {
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
                    </dependencies>

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
                .replace("{{artefacto}}", Nombres.identificador(o.nombreProyecto()).replace('_', '-'))
                .replace("{{proyecto}}", o.nombreProyecto());
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
     * La consola de prueba que viaja DENTRO del backend generado.
     *
     * Responde al requisito de "probar el backend con un frontend hecho en
     * cinco minutos": son cero minutos, porque ya viene en el zip.
     *
     * Tres decisiones, y las tres son por lo mismo --que no pueda fallar el
     * dia de la demo--:
     *
     *   - va en `resources/static/`, asi que la sirve el mismo Spring Boot en
     *     `GET /`. MISMO ORIGEN: no hay CORS que configurar, que es lo que
     *     rompe a cualquier frontend separado;
     *   - un solo archivo, sin Node, sin npm install, sin build. Lo unico que
     *     hace falta es el jar que ya se esta corriendo;
     *   - se arma leyendo `app-manifest.json`, el MISMO contrato que consume
     *     la app movil (ADR-011). No conoce ninguna entidad de antemano, asi
     *     que sirve para cualquier dominio que traiga el docente, y si el
     *     manifiesto esta mal se nota aca antes que en el telefono.
     */
    private static String consola() {
        return """
                <!DOCTYPE html>
                <html lang="es">
                <head>
                <meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <title>Consola de prueba</title>
                <style>
                  * { box-sizing: border-box; }
                  body { margin: 0; font: 14px system-ui, sans-serif; color: #1e293b; background: #f8fafc; }
                  header { background: #0f172a; color: #fff; padding: 14px 20px; }
                  header h1 { margin: 0; font-size: 16px; font-weight: 600; }
                  header span { opacity: .7; font-size: 12px; }
                  main { display: flex; align-items: flex-start; gap: 20px; padding: 20px; flex-wrap: wrap; }
                  nav { min-width: 170px; }
                  nav button { display: block; width: 100%; text-align: left; padding: 8px 12px;
                               margin-bottom: 4px; border: 1px solid #cbd5e1; border-radius: 6px;
                               background: #fff; cursor: pointer; font: inherit; }
                  nav button.activa { background: #0f172a; color: #fff; border-color: #0f172a; }
                  section { flex: 1; min-width: 320px; }
                  .panel { background: #fff; border: 1px solid #e2e8f0; border-radius: 8px;
                           padding: 16px; margin-bottom: 16px; }
                  .panel h2 { margin: 0 0 12px; font-size: 14px; }
                  label { display: block; margin-bottom: 8px; font-size: 12px; color: #475569; }
                  label span { display: block; margin-bottom: 3px; }
                  input, select { width: 100%; padding: 6px 8px; border: 1px solid #cbd5e1;
                                  border-radius: 5px; font: inherit; }
                  button.accion { padding: 8px 16px; border: 0; border-radius: 6px;
                                  background: #2563eb; color: #fff; cursor: pointer; font: inherit; }
                  table { width: 100%; border-collapse: collapse; font-size: 12px; }
                  th, td { text-align: left; padding: 6px 8px; border-bottom: 1px solid #e2e8f0;
                           white-space: nowrap; }
                  th { color: #64748b; font-weight: 600; }
                  .vacio { color: #94a3b8; font-style: italic; }
                  .error { background: #fef2f2; border-color: #fecaca; color: #991b1b; }
                  .borrar { border: 0; background: none; color: #dc2626; cursor: pointer; font: inherit; }
                  .req { color: #dc2626; }
                </style>
                </head>
                <body>
                <header>
                  <h1 id="titulo">Consola de prueba</h1>
                  <span>Se arma sola desde app-manifest.json &middot;
                        <a href="/swagger-ui.html" style="color:#93c5fd">Swagger</a> &middot;
                        <a href="/app-manifest.json" style="color:#93c5fd">manifiesto</a></span>
                </header>
                <main>
                  <nav id="menu"></nav>
                  <section id="cuerpo"></section>
                </main>
                <script>
                let manifiesto = null;
                let actual = null;

                const $ = (id) => document.getElementById(id);

                // El endpoint del manifiesto ya trae /api; baseUrl viene vacio
                // a proposito para que todo resuelva contra este mismo host.
                const url = (e, extra) => (manifiesto.baseUrl || '') + e.endpoint + (extra || '');

                async function arrancar() {
                  try {
                    const r = await fetch('app-manifest.json');
                    if (!r.ok) throw new Error('HTTP ' + r.status);
                    manifiesto = await r.json();
                  } catch (err) {
                    $('cuerpo').innerHTML =
                      '<div class="panel error">No se pudo leer app-manifest.json: ' + err.message + '</div>';
                    return;
                  }
                  $('titulo').textContent = manifiesto.app || 'Consola de prueba';
                  document.title = $('titulo').textContent;
                  if (!manifiesto.entities.length) {
                    $('cuerpo').innerHTML = '<div class="panel vacio">El modelo no genero ninguna entidad.</div>';
                    return;
                  }
                  dibujarMenu();
                  elegir(manifiesto.entities[0]);
                }

                function dibujarMenu() {
                  const menu = $('menu');
                  menu.innerHTML = '';
                  manifiesto.entities.forEach((e) => {
                    const b = document.createElement('button');
                    b.textContent = e.name;
                    b.onclick = () => elegir(e);
                    b.dataset.nombre = e.name;
                    menu.appendChild(b);
                  });
                }

                function elegir(entidad) {
                  actual = entidad;
                  [...$('menu').children].forEach((b) =>
                    b.classList.toggle('activa', b.dataset.nombre === entidad.name));
                  dibujarFormulario();
                  listar();
                }

                function dibujarFormulario() {
                  const puedeCrear = actual.actions.includes('crear');
                  const panel = document.createElement('div');
                  panel.className = 'panel';
                  panel.id = 'panel-alta';
                  panel.innerHTML = '<h2>' + actual.name + ' &middot; ' + actual.endpoint + '</h2>';

                  if (!puedeCrear) {
                    panel.innerHTML += '<p class="vacio">Es una raiz abstracta: solo se puede listar.</p>';
                  } else {
                    const form = document.createElement('form');
                    actual.fields.forEach((f) => {
                      const lab = document.createElement('label');
                      const cap = document.createElement('span');
                      cap.innerHTML = f.name + ' <em>' + f.type + '</em>' +
                        (f.required ? ' <span class="req">*</span>' : '') +
                        (f.target ? ' &rarr; ' + f.target : '');
                      lab.appendChild(cap);
                      lab.appendChild(control(f));
                      form.appendChild(lab);
                    });
                    const btn = document.createElement('button');
                    btn.className = 'accion';
                    btn.type = 'submit';
                    btn.textContent = 'Crear';
                    form.appendChild(btn);
                    form.onsubmit = (ev) => { ev.preventDefault(); crear(form); };
                    panel.appendChild(form);
                  }
                  const cuerpo = $('cuerpo');
                  cuerpo.innerHTML = '';
                  cuerpo.appendChild(panel);
                  const lista = document.createElement('div');
                  lista.className = 'panel';
                  lista.id = 'panel-lista';
                  cuerpo.appendChild(lista);
                }

                function control(f) {
                  if (f.type === 'boolean') {
                    const s = document.createElement('select');
                    s.innerHTML = '<option value="">(vacio)</option>' +
                                  '<option value="true">true</option><option value="false">false</option>';
                    s.name = f.name;
                    return s;
                  }
                  const i = document.createElement('input');
                  i.name = f.name;
                  if (f.type === 'int' || f.type === 'long') i.type = 'number';
                  else if (f.type === 'decimal') { i.type = 'number'; i.step = 'any'; }
                  else if (f.type === 'date') i.type = 'date';
                  else if (f.type === 'datetime') i.type = 'datetime-local';
                  if (f.required) i.required = true;
                  i.placeholder = f.type;
                  return i;
                }

                function valor(f, crudo) {
                  if (crudo === '' || crudo === null) return null;
                  if (f.type === 'int' || f.type === 'long') return parseInt(crudo, 10);
                  if (f.type === 'decimal') return parseFloat(crudo);
                  if (f.type === 'boolean') return crudo === 'true';
                  return crudo;
                }

                async function crear(form) {
                  const cuerpo = {};
                  actual.fields.forEach((f) => {
                    const v = valor(f, form.elements[f.name].value);
                    if (v !== null) cuerpo[f.name] = v;
                  });
                  try {
                    const r = await fetch(url(actual), {
                      method: 'POST',
                      headers: { 'Content-Type': 'application/json' },
                      body: JSON.stringify(cuerpo),
                    });
                    if (!r.ok) {
                      avisar('La API respondio ' + r.status + ': ' + (await r.text()));
                      return;
                    }
                    form.reset();
                    listar();
                  } catch (err) {
                    avisar('No se pudo llamar a la API: ' + err.message);
                  }
                }

                async function borrar(id) {
                  try {
                    const r = await fetch(url(actual, '/' + id), { method: 'DELETE' });
                    if (!r.ok) { avisar('No se pudo eliminar: HTTP ' + r.status); return; }
                    listar();
                  } catch (err) {
                    avisar('No se pudo llamar a la API: ' + err.message);
                  }
                }

                async function listar() {
                  const panel = $('panel-lista');
                  panel.className = 'panel';
                  panel.innerHTML = '<h2>Registros</h2>';
                  let filas;
                  try {
                    const r = await fetch(url(actual));
                    if (!r.ok) { panel.className = 'panel error'; panel.innerHTML +=
                      'La API respondio ' + r.status; return; }
                    filas = await r.json();
                  } catch (err) {
                    panel.className = 'panel error';
                    panel.innerHTML += 'No se pudo llamar a la API: ' + err.message;
                    return;
                  }
                  if (!Array.isArray(filas) || !filas.length) {
                    panel.innerHTML += '<p class="vacio">Todavia no hay registros.</p>';
                    return;
                  }
                  const columnas = Object.keys(filas[0]);
                  const tabla = document.createElement('table');
                  const thead = document.createElement('tr');
                  columnas.forEach((c) => {
                    const th = document.createElement('th');
                    th.textContent = c;
                    thead.appendChild(th);
                  });
                  thead.appendChild(document.createElement('th'));
                  tabla.appendChild(thead);
                  filas.forEach((fila) => {
                    const tr = document.createElement('tr');
                    columnas.forEach((c) => {
                      const td = document.createElement('td');
                      td.textContent = fila[c] === null ? '' : String(fila[c]);
                      tr.appendChild(td);
                    });
                    const td = document.createElement('td');
                    if (actual.actions.includes('eliminar') && fila.id) {
                      const b = document.createElement('button');
                      b.className = 'borrar';
                      b.textContent = 'eliminar';
                      b.onclick = () => borrar(fila.id);
                      td.appendChild(b);
                    }
                    tr.appendChild(td);
                    tabla.appendChild(tr);
                  });
                  panel.appendChild(tabla);
                }

                function avisar(texto) {
                  const panel = $('panel-alta');
                  let caja = document.getElementById('aviso');
                  if (!caja) {
                    caja = document.createElement('p');
                    caja.id = 'aviso';
                    panel.appendChild(caja);
                  }
                  caja.className = 'req';
                  caja.textContent = texto;
                }

                arrancar();
                </script>
                </body>
                </html>
                """;
    }

    /**
     * CORS abierto en el backend generado.
     *
     * Hace falta para que un frontend servido desde OTRO origen --Flutter web,
     * una pagina suelta, un Vite-- pueda llamar a esta API: sin esto el
     * navegador bloquea la respuesta, y parece un error del cliente cuando es
     * del servidor.
     *
     * NO hace falta para Flutter en el telefono: CORS es una regla del
     * navegador, y un cliente HTTP nativo no la aplica.
     *
     * Abierto a proposito: esto es un artefacto de demostracion que vive en una
     * red de aula, no un servicio publicado. Restringir origenes aca seria
     * pedirle al docente que configure algo antes de poder probar.
     */
    private static String cors(String pkg) {
        return """
                package {{pkg}}.config;

                import org.springframework.context.annotation.Configuration;
                import org.springframework.web.servlet.config.annotation.CorsRegistry;
                import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

                @Configuration
                public class CorsConfig implements WebMvcConfigurer {

                    @Override
                    public void addCorsMappings(CorsRegistry registry) {
                        registry.addMapping("/**")
                                .allowedOriginPatterns("*")
                                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                                .allowedHeaders("*");
                    }
                }
                """.replace("{{pkg}}", pkg);
    }

    private static String configuracion(Opciones o) {
        return """
                server:
                  port: 8081

                spring:
                  application:
                    name: {{proyecto}}
                  # Sin perfil: H2 en memoria. Arranca sin instalar ni levantar nada,
                  # que es lo que hace que este artefacto se pueda probar en cualquier
                  # maquina. PostgreSQL queda a un `--spring.profiles.active=postgres`
                  # de distancia, con el MISMO DDL: el esquema no se bifurca.
                  profiles:
                    default: h2
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
                      on-profile: h2
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

                ---
                spring:
                  config:
                    activate:
                      on-profile: postgres
                  datasource:
                    url: jdbc:postgresql://localhost:5433/{{db}}
                    username: colabmodel
                    password: colabmodel
                  flyway:
                    locations: classpath:db/migration
                """
                .replace("{{proyecto}}", o.nombreProyecto())
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
                                  List<ModeloLogico.Incrustado> incrustados) {
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
                !colecciones.isEmpty() || !valores.isEmpty(), herencia);
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

    private static String accesores(String tipo, String campo) {
        return "    public " + tipo + " " + NombresJava.getter(campo) + "() { return " + campo + "; }\n"
                + "    public void " + NombresJava.setter(campo) + "(" + tipo + " " + campo
                + ") { this." + campo + " = " + campo + "; }\n";
    }

    private static String entidadTexto(String pkg, String clase, String tabla, List<Campo> campos,
                                       String relaciones, String accesoresRel, boolean conListas,
                                       Herencia herencia) {
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

                {{campos}}{{relaciones}}{{accesores}}{{accesoresRel}}{{igualdad}}}
                """
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
                .replace("{{importsLista}}", conListas ? "import java.util.ArrayList;\nimport java.util.List;\n" : "")
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
        // un cliente" sea una consulta y no traerse la tabla entera.
        StringBuilder porFk = new StringBuilder();
        for (Asociacion a : asociaciones) {
            porFk.append("\n    /** El lado inverso de ").append(a.campo())
                    .append(": lo que `inverseOf` promete en app-manifest.json. */\n")
                    .append("    @EntityGraph(attributePaths = { ").append(rutas).append(" })\n")
                    .append("    List<").append(clase).append("> findBy")
                    .append(mayuscula(a.campo())).append("_Id(UUID ").append(a.campo()).append("Id);\n");
        }

        return """
                package {{pkg}}.repository;

                import {{pkg}}.domain.{{clase}};
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
                {{porFk}}}
                """.replace("{{pkg}}", pkg).replace("{{rutas}}", rutas)
                .replace("{{porFk}}", porFk.toString()).replace("{{clase}}", clase);
    }

    /** Primera letra en mayuscula, para armar `findByCliente_Id` desde `cliente`. */
    private static String mayuscula(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static String servicio(String pkg, String clase, List<Campo> campos,
                                   List<Asociacion> asociaciones, List<ColeccionDeValores> valores,
                                   List<ModeloLogico.Incrustado> incrustados,
                                   Stubs stubs, boolean instanciable) {
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
                import org.springframework.stereotype.Service;
                import org.springframework.transaction.annotation.Transactional;

                {{importArrayList}}import java.util.List;
                import java.util.NoSuchElementException;
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

                    private final {{clase}}Repository repositorio;
                {{deps}}
                    public {{clase}}Service({{clase}}Repository repositorio{{params}}) {
                        this.repositorio = repositorio;
                {{asigna}}    }

                    @Transactional(readOnly = true)
                    public List<{{clase}}Dto> listar() {
                        return repositorio.findAll().stream().map({{clase}}Service::aDto).toList();
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
                        repositorio.deleteById(id);
                        // Flush aca y no al commit: si una FK impide el borrado, la
                        // excepcion sale traducida y el manejador responde 409, no 500.
                        repositorio.flush();
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
                // Una clase abstracta no se instancia: su API lista, obtiene y
                // elimina (polimorficamente), y se crea a traves de sus hijas.
                .replace("{{escritura}}", !instanciable ? "" : """
                            @Transactional
                            public {{clase}}Dto crear({{clase}}Dto dto) {
                                {{clase}} entidad = new {{clase}}();
                                copiar(dto, entidad);
                                return aDto(repositorio.saveAndFlush(entidad));
                            }

                            @Transactional
                            public {{clase}}Dto actualizar(UUID id, {{clase}}Dto dto) {
                                {{clase}} entidad = buscar(id);
                                copiar(dto, entidad);
                                return aDto(repositorio.saveAndFlush(entidad));
                            }

                        """)
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
            salida.append("\n    @Transactional(readOnly = true)\n")
                    .append("    public List<").append(clase).append("Dto> listarPor")
                    .append(mayuscula(a.campo())).append("(UUID ").append(a.campo()).append("Id) {\n")
                    .append("        return repositorio.findBy").append(mayuscula(a.campo()))
                    .append("_Id(").append(a.campo()).append("Id).stream().map(")
                    .append(clase).append("Service::aDto).toList();\n    }\n");
        }
        return salida.toString();
    }

    /** El codigo de los stubs y los imports que hacen falta para compilarlos. */
    private record Stubs(String codigo, List<String> imports) {
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
                              List<ModeloLogico.Incrustado> incrustados) {
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

    /**
     * El REST de una entidad.
     *
     * EL LISTADO ACEPTA UN FILTRO POR CADA FK, con el mismo nombre con que el
     * id viaja en el DTO y en `app-manifest.json` (`clienteId`). Sin esto, el
     * `inverseOf` del manifiesto era una promesa que la API no cumplia: un
     * frontend que queria "los pedidos de este cliente" tenia que pedir
     * `GET /api/pedido` entero y filtrar en el navegador.
     *
     * RECORTE DECLARADO: se aplica UN filtro, el primero que venga. Cruzar dos
     * necesita Specifications y nadie lo pidio todavia; combinarlos mal en
     * silencio seria peor que no ofrecerlo.
     */
    private static String controlador(String pkg, String clase, String recurso, boolean instanciable,
                                      List<Asociacion> asociaciones) {
        // La continuacion se alinea con el parentesis de `listar(`, que depende
        // del largo del nombre de la clase. El texto de la plantilla no puede
        // saberlo, asi que se calcula aca.
        String sangria = " ".repeat(("    public List<" + clase + "Dto> listar(").length());
        StringBuilder parametros = new StringBuilder();
        StringBuilder despacho = new StringBuilder();
        for (Asociacion a : asociaciones) {
            String param = a.campo() + "Id";
            parametros.append(parametros.isEmpty() ? "" : ",\n" + sangria)
                    .append("@RequestParam(required = false) UUID ").append(param);
            despacho.append("        if (").append(param).append(" != null) return servicio.listarPor")
                    .append(mayuscula(a.campo())).append("(").append(param).append(");\n");
        }

        return """
                package {{pkg}}.web;

                import {{pkg}}.service.{{clase}}Service;
                import {{pkg}}.web.dto.{{clase}}Dto;
                import jakarta.validation.Valid;
                import org.springframework.http.ResponseEntity;
                import org.springframework.web.bind.annotation.*;

                import java.util.List;
                import java.util.UUID;

                /** Solo DTOs: la entidad JPA no cruza nunca esta frontera. */
                @RestController
                @RequestMapping("/api/{{recurso}}")
                public class {{clase}}Controller {

                    private final {{clase}}Service servicio;

                    public {{clase}}Controller({{clase}}Service servicio) {
                        this.servicio = servicio;
                    }

                    @GetMapping
                    public List<{{clase}}Dto> listar({{parametros}}) {
                {{despacho}}        return servicio.listar();
                    }

                    @GetMapping("/{id}")
                    public {{clase}}Dto obtener(@PathVariable UUID id) {
                        return servicio.obtener(id);
                    }

                {{escritura}}
                    @DeleteMapping("/{id}")
                    public ResponseEntity<Void> eliminar(@PathVariable UUID id) {
                        servicio.eliminar(id);
                        return ResponseEntity.noContent().build();
                    }
                }
                """
                .replace("{{pkg}}", pkg)
                .replace("{{escritura}}", !instanciable ? "" : """
                            @PostMapping
                            public ResponseEntity<{{clase}}Dto> crear(@Valid @RequestBody {{clase}}Dto dto) {
                                return ResponseEntity.ok(servicio.crear(dto));
                            }

                            @PutMapping("/{id}")
                            public {{clase}}Dto actualizar(@PathVariable UUID id, @Valid @RequestBody {{clase}}Dto dto) {
                                return servicio.actualizar(id, dto);
                            }

                        """)
                .replace("{{parametros}}", parametros.toString())
                .replace("{{despacho}}", despacho.toString())
                .replace("{{recurso}}", recursoDe(recurso).substring("/api/".length()))
                .replace("{{clase}}", clase);
    }

    /**
     * Codigos HTTP honestos para los errores que un cliente provoca siempre:
     * pedir algo que no existe, mandar un cuerpo invalido, escribir mal un id
     * en la URL y romper una restriccion de la base. Sin esto los cuatro
     * salian como 500 o con el JSON por defecto de Spring, que no tiene la
     * misma forma que el resto de la API.
     *
     * EL 400 DE VALIDACION DEVUELVE EL CAMPO, no solo un mensaje. Las
     * anotaciones (@NotNull, @Size, @Min) ya se generan en el DTO desde el
     * modelo, asi que el backend sabe exactamente cual fallo; si no lo dice,
     * el formulario del frontend no puede marcarlo y el usuario tiene que
     * adivinar. La clave del mapa es el nombre del campo TAL CUAL viaja en el
     * JSON y en `app-manifest.json`, que es con lo que el cliente pinta el
     * formulario.
     */
    private static String manejadorDeErrores(String pkg) {
        return """
                package {{pkg}}.web;

                import org.springframework.dao.DataIntegrityViolationException;
                import org.springframework.http.HttpStatus;
                import org.springframework.http.ResponseEntity;
                import org.springframework.validation.FieldError;
                import org.springframework.web.bind.MethodArgumentNotValidException;
                import org.springframework.web.bind.annotation.ExceptionHandler;
                import org.springframework.web.bind.annotation.RestControllerAdvice;
                import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

                import java.util.LinkedHashMap;
                import java.util.Map;
                import java.util.NoSuchElementException;

                @RestControllerAdvice
                public class ManejadorDeErrores {

                    /** Un id que no existe es un 404, no una falla del servidor. */
                    @ExceptionHandler(NoSuchElementException.class)
                    public ResponseEntity<Map<String, String>> noEncontrado(NoSuchElementException e) {
                        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                                .body(Map.of("error", String.valueOf(e.getMessage())));
                    }

                    /**
                     * Cuerpo que no pasa las validaciones del DTO.
                     *
                     * Se devuelve `errores` campo por campo para que un formulario
                     * pueda marcar el que fallo. Si un campo junta dos violaciones
                     * se informa la primera: una por campo es lo que un formulario
                     * sabe mostrar.
                     */
                    @ExceptionHandler(MethodArgumentNotValidException.class)
                    public ResponseEntity<Map<String, Object>> invalido(MethodArgumentNotValidException e) {
                        Map<String, String> errores = new LinkedHashMap<>();
                        for (FieldError f : e.getBindingResult().getFieldErrors()) {
                            errores.putIfAbsent(f.getField(), f.getDefaultMessage());
                        }
                        return ResponseEntity.badRequest().body(Map.of(
                                "error", "Hay campos invalidos", "errores", errores));
                    }

                    /** Un uuid mal escrito en la URL o en un filtro es culpa del cliente. */
                    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
                    public ResponseEntity<Map<String, String>> tipoInvalido(
                            MethodArgumentTypeMismatchException e) {
                        return ResponseEntity.badRequest().body(Map.of("error",
                                "El valor de '" + e.getName() + "' no tiene el formato esperado"));
                    }

                    /** Clave unica repetida, o borrar una fila que otra todavia referencia. */
                    @ExceptionHandler(DataIntegrityViolationException.class)
                    public ResponseEntity<Map<String, String>> conflicto(DataIntegrityViolationException e) {
                        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error",
                                "La operacion viola una restriccion de la base: un valor unico repetido,"
                                        + " o una fila que otra todavia referencia."));
                    }
                }
                """.replace("{{pkg}}", pkg);
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

    private static String readme(Opciones o, int entidades, List<String> advertencias) {
        String lista = advertencias.isEmpty()
                ? "Ninguna: el modelo se mapeo completo.\n"
                : advertencias.stream().map(a -> "- " + a + "\n").reduce("", String::concat);
        return """
                # {{proyecto}}

                Generado por ColabModel desde el modelo conceptual. **No edites a mano**:
                si volves a generar, se pisa.

                Entidades generadas: {{entidades}}. Cada una trae 5 capas
                (Entity, Repository, Service, DTO y Controller) y su DDL de Flyway.

                ## Como levantarlo

                ```bash
                mvn spring-boot:run
                ```

                **No hace falta instalar ni levantar nada mas.** Por defecto usa H2 en
                memoria, con el esquema creado por Flyway y unas filas de ejemplo ya
                cargadas. La base se borra al apagar, que es justo lo que se quiere para
                probar.

                Para usar PostgreSQL --misma aplicacion, mismo DDL, sin datos de
                ejemplo--:

                ```bash
                mvn spring-boot:run -Dspring-boot.run.profiles=postgres
                ```

                Los datos de conexion estan en `src/main/resources/application.yml`.
                Hibernate solo VALIDA el esquema: si el mapeo y el DDL no coinciden, la
                aplicacion no arranca, y eso es el sistema funcionando.

                ## Como probarlo

                Apenas arranca, sin instalar ni escribir nada:

                | Donde | Que es |
                |---|---|
                | `/` | Consola de prueba: un ABM por entidad, servido por este mismo backend |
                | `/swagger-ui.html` | La API completa, para probarla a mano |
                | `/app-manifest.json` | El contrato que lee la app movil (ADR-011) |

                La consola se arma leyendo el manifiesto, igual que la app movil: no
                conoce ninguna entidad de antemano. Como la sirve este mismo backend,
                comparte origen con la API y no hay CORS que configurar.

                ### Desde el telefono

                La app movil lee `/app-manifest.json` y aprende el dominio sola, asi que
                el mismo APK sirve para cualquier modelo. Dos cosas del dia de la demo:

                - usa la **IP de esta maquina en la red**, no `localhost`: para el
                  telefono, `localhost` es el telefono;
                - Android 9 y posteriores bloquean HTTP sin TLS, asi que la app tiene que
                  declarar `usesCleartextTraffic`.

                `baseUrl` viene **vacio** a proposito en el manifiesto: significa "resolve
                las rutas contra el host del que me bajaste". Cualquier IP escrita ahi
                quedaria vieja apenas cambie la red.

                ## Decisiones tomadas y limites de esta version

                {{advertencias}}
                Las asociaciones se emiten completas (Regla 7): `@ManyToOne` con
                `LAZY` explicito, el lado inverso con `mappedBy`, el cascade que
                corresponde al tipo de relacion —la composicion arrastra a la parte,
                la agregacion no— y `@EntityGraph` en los listados para no caer en
                N+1.

                Todavia NO se emiten: `@ManyToMany` para las N:M (su tabla puente no
                genera Entity), ni `@Inheritance(JOINED)` con `extends` para la
                herencia, que en el DDL ya comparte la PK.
                """
                .replace("{{proyecto}}", o.nombreProyecto())
                .replace("{{entidades}}", String.valueOf(entidades))
                .replace("{{advertencias}}", lista);
    }
}
