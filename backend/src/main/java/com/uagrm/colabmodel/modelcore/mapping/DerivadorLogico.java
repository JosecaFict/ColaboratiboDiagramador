package com.uagrm.colabmodel.modelcore.mapping;

import com.uagrm.colabmodel.modelcore.metamodel.*;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Deriva la Vista Logica del metamodelo conceptual (docs/REGLAS-MAPEO.md).
 *
 * Funcion PURA a proposito: entra el modelo, sale el esquema. Eso la hace
 * verificable contra los 7 modelos canonicos, y permite que mas adelante
 * `codegen` emita el DDL desde exactamente la misma derivacion que el usuario
 * vio en pantalla. Si la vista y el generador usaran codigo distinto, lo que
 * se demuestra no seria lo que se entrega.
 *
 * Reglas cubiertas: 1 (identidad), 2 (tabla<->clase), 3 (relaciones),
 * 4 (herencia JOINED), 8 (identificadores), 9 (CHECK de enumeraciones),
 * 10 (no mapeable), 11 (entidad debil), 12 (multivaluado).
 */
public final class DerivadorLogico {

    private DerivadorLogico() { }

    /** Sin decisiones tomadas: cada ambiguedad usa su lectura por defecto. */
    public static ModeloLogico derivar(List<UmlClass> clases,
                                       List<UmlAttribute> atributos,
                                       List<UmlRelationship> relaciones) {
        return derivar(clases, atributos, relaciones, Decisiones.ninguna());
    }

    public static ModeloLogico derivar(List<UmlClass> clases,
                                       List<UmlAttribute> atributos,
                                       List<UmlRelationship> relaciones,
                                       Decisiones decisiones) {
        List<String> advertencias = new ArrayList<>();

        List<UmlClass> vivas = clases.stream().filter(c -> c.getDeletedAt() == null).toList();
        Map<UUID, UmlClass> porId = vivas.stream().collect(Collectors.toMap(UmlClass::getId, Function.identity()));
        Map<UUID, List<UmlAttribute>> atributosPorClase = atributos.stream()
                .collect(Collectors.groupingBy(UmlAttribute::getClassId));
        Map<String, ModeloLogico.Enumeracion> enums = Enumeraciones.de(vivas, atributos, advertencias);

        List<UmlRelationship> usables = new ArrayList<>();
        for (UmlRelationship r : relaciones) {
            if (r.getDeletedAt() != null) continue;
            if (!porId.containsKey(r.getSourceClassId()) || !porId.containsKey(r.getTargetClassId())) continue;
            // Una enumeracion es un TIPO, no una entidad: no tiene filas a las
            // que apuntar. Se usa como tipo de un atributo, no relacionandola.
            // Una clase «embeddable» solo se compone 1..1 (y la compone el duenio):
            // cualquier otra relacion necesitaria que tuviera filas propias.
            UmlClass destinoRel = porId.get(r.getTargetClassId());
            boolean tocaIncrustable = Incrustables.es(porId.get(r.getSourceClassId())) || Incrustables.es(destinoRel);
            boolean composicionDeValor = r.getKind() == RelationKind.COMPOSITION && Incrustables.es(destinoRel)
                    && !Incrustables.es(porId.get(r.getSourceClassId()))
                    && !esMuchos(r.getSourceMultiplicity()) && !esMuchos(r.getTargetMultiplicity());
            if (tocaIncrustable && !composicionDeValor) {
                advertencias.add("La relacion entre " + nombre(porId, r.getSourceClassId()) + " y "
                        + nombre(porId, r.getTargetClassId()) + " toca una clase «embeddable» y se ignora:"
                        + " un valor no tiene identidad propia. Usala como tipo de un atributo o"
                        + " componela 1..1 (Regla 5).");
                continue;
            }
            if (Enumeraciones.es(porId.get(r.getSourceClassId()))
                    || Enumeraciones.es(porId.get(r.getTargetClassId()))) {
                advertencias.add("La relacion entre " + nombre(porId, r.getSourceClassId()) + " y "
                        + nombre(porId, r.getTargetClassId()) + " toca una enumeracion y se ignora:"
                        + " para usarla, ponela como tipo de un atributo (Regla 10).");
                continue;
            }
            if (r.getDegree() > 2) {
                advertencias.add("Relacion n-aria de grado " + r.getDegree()
                        + ": no es mapeable (Regla 10). Reificala como clase asociativa.");
                continue;
            }
            if (r.getKind() == RelationKind.DEPENDENCY) {
                advertencias.add("La dependencia entre " + nombre(porId, r.getSourceClassId()) + " y "
                        + nombre(porId, r.getTargetClassId()) + " no genera nada en el esquema.");
                continue;
            }
            usables.add(r);
        }

        // Las decisiones OO<->Datos se resuelven ANTES de construir nada: una
        // de ellas (el @Embeddable) borra tablas enteras, asi que no se puede
        // aplicar a posteriori sobre un esquema ya armado.
        Plan plan = planificar(usables, atributos, porId, decisiones, advertencias);

        Map<UUID, Borrador> tablas = new LinkedHashMap<>();
        List<Borrador> extras = new ArrayList<>();   // multivaluados y tablas puente

        for (UmlClass c : vivas) {
            if (partesDe(plan).contains(c.getId())) continue;   // vive dentro de su duenio
            if (Enumeraciones.es(c)) continue;                      // es un tipo, no una tabla
            if (Incrustables.es(c)) continue;                       // vive dentro de su duenio
            Borrador t = new Borrador(Nombres.tabla(c.getName()), c.getName(), c.getId());
            tablas.put(c.getId(), t);
            construirColumnas(c, atributosPorClase.getOrDefault(c.getId(), List.of()),
                    t, extras, usables, porId, advertencias, plan, atributosPorClase, enums);
        }

        for (UmlRelationship r : usables) {
            if (plan.relacionesAbsorbidas.contains(r.getId())) continue;   // la parte se aplano
            if (r.getKind() == RelationKind.GENERALIZATION) {
                aplicarHerencia(r, porId, tablas, advertencias);
            } else if (!esIdentificadoraDeDebil(r, porId, plan)) {
                aplicarAsociacion(r, porId, tablas, extras, advertencias, plan);
            }
        }

        fundirJerarquiasEnTablaUnica(vivas, usables, tablas, extras, advertencias);

        List<ModeloLogico.Tabla> resultado = new ArrayList<>();
        new LinkedHashSet<>(tablas.values()).forEach(b -> resultado.add(b.aTabla()));
        extras.forEach(b -> resultado.add(b.aTabla()));
        return new ModeloLogico(resultado, advertencias);
    }

    // ------------------------------------------------------- SINGLE_TABLE

    /**
     * REGLA 4, opcion SINGLE_TABLE: la jerarquia de una raiz marcada asi se
     * guarda en la tabla de la raiz, con una columna `tipo`.
     *
     * Se hace DESPUES de la derivacion JOINED y fundiendo sus tablas, en vez de
     * construir distinto desde el principio: asi JOINED sigue siendo el unico
     * camino que arma columnas, y esto solo las mueve.
     */
    private static void fundirJerarquiasEnTablaUnica(List<UmlClass> vivas, List<UmlRelationship> usables,
                                                     Map<UUID, Borrador> tablas, List<Borrador> extras,
                                                     List<String> advertencias) {
        Map<UUID, UmlClass> porId = new HashMap<>();
        vivas.forEach(c -> porId.put(c.getId(), c));
        Map<UUID, List<UUID>> hijasDe = new LinkedHashMap<>();
        Set<UUID> tienenMadre = new HashSet<>();
        for (UmlRelationship r : usables) {
            if (r.getKind() != RelationKind.GENERALIZATION) continue;
            if (r.getSourceClassId().equals(r.getTargetClassId())) continue;
            hijasDe.computeIfAbsent(r.getTargetClassId(), k -> new ArrayList<>()).add(r.getSourceClassId());
            tienenMadre.add(r.getSourceClassId());
        }

        for (UmlClass raiz : vivas) {
            if (raiz.getInheritanceStrategy() != InheritanceStrategy.SINGLE_TABLE) continue;
            if (tienenMadre.contains(raiz.getId())) {
                advertencias.add(raiz.getName() + " esta marcada SINGLE_TABLE pero no es la raiz de su"
                        + " jerarquia: la estrategia se elige en la raiz (Regla 4).");
                continue;
            }
            Borrador tablaRaiz = tablas.get(raiz.getId());
            if (tablaRaiz == null || !hijasDe.containsKey(raiz.getId())) continue;

            // Descendientes en orden: primero los hijos directos, despues los nietos.
            List<UUID> descendientes = new ArrayList<>();
            Map<UUID, UUID> madreDe = new HashMap<>();
            Deque<UUID> pendientes = new ArrayDeque<>(List.of(raiz.getId()));
            Set<UUID> vistos = new HashSet<>(List.of(raiz.getId()));
            while (!pendientes.isEmpty()) {
                UUID actual = pendientes.poll();
                for (UUID hija : hijasDe.getOrDefault(actual, List.of())) {
                    if (!vistos.add(hija)) continue;
                    descendientes.add(hija);
                    madreDe.put(hija, actual);
                    pendientes.add(hija);
                }
            }

            String motivo = motivoParaNoFundir(descendientes, porId, tablas, extras);
            if (motivo != null) {
                advertencias.add("La jerarquia de " + raiz.getName() + " no se puede guardar en una sola"
                        + " tabla porque " + motivo + ": se mantiene JOINED (Regla 4/10).");
                continue;
            }

            List<ModeloLogico.Miembro> miembros = new ArrayList<>();
            miembros.add(new ModeloLogico.Miembro(raiz.getName(), null, valorDe(raiz, hijasDe),
                    tablaRaiz.columnas.stream().map(ModeloLogico.Columna::nombre).toList(), List.of()));

            for (UUID id : descendientes) {
                UmlClass d = porId.get(id);
                Borrador suya = tablas.get(id);
                List<String> columnas = new ArrayList<>();
                List<String> obligatorias = new ArrayList<>();
                for (ModeloLogico.Columna c : suya.columnas) {
                    if (c.nombre().equals("id")) continue;   // la PK compartida desaparece
                    // Las filas de las OTRAS subclases no tienen esta columna:
                    // en la base tiene que admitir NULL.
                    String final_ = tablaRaiz.agregar(c.como(c.nombre(), false, true), advertencias);
                    columnas.add(final_);
                    if (!c.nullable()) {
                        obligatorias.add(final_);
                        advertencias.add(d.getName() + "." + c.nombre() + " es obligatorio, pero en SINGLE_TABLE"
                                + " la columna admite NULL (las filas de otras clases no la tienen): el"
                                + " obligatorio queda validado en el DTO, no en la base (Regla 4).");
                    }
                }
                miembros.add(new ModeloLogico.Miembro(d.getName(), porId.get(madreDe.get(id)).getName(),
                        valorDe(d, hijasDe), List.copyOf(columnas), List.copyOf(obligatorias)));
                tablas.put(id, tablaRaiz);   // quien busque la tabla de la hija, encuentra la unica
            }

            // El discriminador solo acepta clases que pueden tener filas.
            List<String> valores = miembros.stream().map(ModeloLogico.Miembro::valor)
                    .filter(Objects::nonNull).toList();
            int largo = Math.max(31, valores.stream().mapToInt(String::length).max().orElse(0));
            // Si el modelo ya tiene un atributo "tipo", el discriminador se renombra:
            // la metadata usa el nombre con el que quedo, no el pedido.
            String discriminador = tablaRaiz.agregar(ModeloLogico.Columna.simple("tipo",
                            "varchar(" + largo + ")", false, false, false)
                    .conEnumeracion(new ModeloLogico.Enumeracion("tipo", valores)), advertencias);
            tablaRaiz.tablaUnica = new ModeloLogico.TablaUnica(discriminador, List.copyOf(miembros));
            tablaRaiz.origen = raiz.getName() + " (SINGLE_TABLE)";
        }
    }

    /** null si se puede fundir; si no, la razon, para decirla. */
    private static String motivoParaNoFundir(List<UUID> descendientes, Map<UUID, UmlClass> porId,
                                             Map<UUID, Borrador> tablas, List<Borrador> extras) {
        Set<String> nombresDescendientes = new HashSet<>();
        for (UUID id : descendientes) {
            Borrador b = tablas.get(id);
            if (b == null) return porId.get(id).getName() + " no tiene tabla propia";
            nombresDescendientes.add(b.nombre);
            List<ModeloLogico.Columna> pks = b.columnas.stream().filter(ModeloLogico.Columna::pk).toList();
            if (pks.size() != 1 || !pks.get(0).nombre().equals("id")) {
                return porId.get(id).getName() + " tiene clave primaria compuesta";
            }
            boolean fkPropia = b.columnas.stream().anyMatch(c -> c.referencia() != null && !c.nombre().equals("id"));
            if (fkPropia) return porId.get(id).getName() + " tiene relaciones propias";
            if (!b.incrustados.isEmpty()) return porId.get(id).getName() + " tiene valores embebidos propios";
        }
        List<Borrador> todas = new ArrayList<>(new LinkedHashSet<>(tablas.values()));
        todas.addAll(extras);
        for (Borrador b : todas) {
            for (ModeloLogico.Columna c : b.columnas) {
                if (c.referencia() == null || c.nombre().equals("id") && nombresDescendientes.contains(b.nombre)) continue;
                String destino = c.referencia().substring(0, c.referencia().indexOf('('));
                if (nombresDescendientes.contains(destino)) {
                    return "la tabla " + b.nombre + " referencia a la subclase " + destino;
                }
            }
        }
        return null;
    }

    /** Lo que guarda el discriminador para esta clase; null si es abstracta y tiene hijas. */
    private static String valorDe(UmlClass c, Map<UUID, List<UUID>> hijasDe) {
        if (c.isAbstract() && hijasDe.containsKey(c.getId())) return null;
        return Enumeraciones.literal(c.getName());
    }

    // ------------------------------------------------------------ DECISIONES

    /**
     * Lo que las decisiones OO<->Datos cambian, resuelto de una vez.
     *
     * @param aplanadas            cada parte aplanada en su duenio (A-2 o «embeddable»). Una
     *                             clase «embeddable» puede aparecer varias veces: un mismo
     *                             valor (Direccion) sirve a varios duenios
     * @param relacionesAbsorbidas relaciones que ya no generan FK porque la parte se aplano
     * @param debilesPorDecision   clase parte -> relacion que la vuelve entidad debil (A-1, lectura Datos)
     * @param puentesConId         tablas puente que llevan id surrogado (A-4, lectura OO)
     * @param colecciones          atributos multivaluados leidos como @ElementCollection (A-3, lectura OO)
     */
    private record Plan(List<Aplanado> aplanadas,
                        Set<UUID> relacionesAbsorbidas,
                        Map<UUID, UmlRelationship> debilesPorDecision,
                        Set<UUID> puentesConId,
                        Set<UUID> colecciones) { }

    private record Aplanado(UUID parteId, UUID todoId, String prefijo) { }

    private static Set<UUID> partesDe(Plan p) {
        Set<UUID> ids = new HashSet<>();
        p.aplanadas().forEach(a -> ids.add(a.parteId()));
        return ids;
    }

    private static Plan planificar(List<UmlRelationship> usables, List<UmlAttribute> atributos,
                                   Map<UUID, UmlClass> porId,
                                   Decisiones decisiones, List<String> advertencias) {
        List<Aplanado> aplanadas = new ArrayList<>();
        Set<UUID> absorbidas = new HashSet<>();
        Map<UUID, UmlRelationship> debiles = new LinkedHashMap<>();
        Set<UUID> puentes = new HashSet<>();

        for (UmlRelationship r : usables) {
            UmlClass origen = porId.get(r.getSourceClassId());
            UmlClass destino = porId.get(r.getTargetClassId());
            if (origen == null || destino == null) continue;
            boolean origenMuchos = esMuchos(r.getSourceMultiplicity());
            boolean destinoMuchos = esMuchos(r.getTargetMultiplicity());

            if (origenMuchos && destinoMuchos) {
                if (decisiones.esOO(Ambiguedad.TipoAmbiguedad.ASSOC_CLASS, r.getId(),
                        Ambiguedad.Lectura.DATA)) {
                    puentes.add(r.getId());
                }
                continue;
            }
            if (r.getKind() != RelationKind.COMPOSITION) continue;

            if (!origenMuchos && !destinoMuchos) {
                // A-2: @Embeddable. La parte deja de tener tabla. Si la parte ya
                // es «embeddable», esta decidido en el modelo y no se pregunta.
                UmlClass parte = destino;
                if (!Incrustables.es(parte) && !decisiones.esOO(Ambiguedad.TipoAmbiguedad.EMBEDDABLE,
                        r.getId(), Ambiguedad.Lectura.DATA)) continue;
                if (parte.getId().equals(origen.getId())) {
                    advertencias.add("La composicion reflexiva de " + parte.getName()
                            + " no se puede aplanar como @Embeddable: se mantiene la tabla (Regla 10).");
                    continue;
                }
                // Aplanar una clase que alguien mas referencia dejaria esas FK
                // apuntando a una tabla que ya no existe. Una «embeddable» no
                // tiene tabla ni otras relaciones: la pueden componer varios.
                long otras = Incrustables.es(parte) ? 0 : usables.stream()
                        .filter(x -> !x.getId().equals(r.getId()))
                        .filter(x -> x.getSourceClassId().equals(parte.getId())
                                || x.getTargetClassId().equals(parte.getId()))
                        .count();
                if (otras > 0) {
                    advertencias.add(parte.getName() + " participa en otras relaciones, asi que no se"
                            + " puede aplanar como @Embeddable: se mantiene su tabla (Regla 10).");
                    continue;
                }
                aplanadas.add(new Aplanado(parte.getId(), origen.getId(),
                        rolO(r.getTargetRole(), parte.getName())));
                absorbidas.add(r.getId());
                continue;
            }

            // A-1: composicion 1:N leida como entidad debil.
            if (decisiones.esOO(Ambiguedad.TipoAmbiguedad.COMPOSITION, r.getId(),
                    Ambiguedad.Lectura.OO)) continue;
            UmlClass parte = destinoMuchos ? destino : origen;
            if (parte.getId().equals(origen.getId()) && parte.getId().equals(destino.getId())) {
                advertencias.add("La composicion reflexiva de " + parte.getName()
                        + " no puede leerse como entidad debil: se mantiene el id surrogado (Regla 10).");
                continue;
            }
            debiles.put(parte.getId(), r);
        }
        // A-3: el multivaluado leido como @ElementCollection. La TABLA es la
        // misma en los dos casos --1FN no se negocia--; lo que cambia es si
        // cuelga del duenio como coleccion de valores o si es una entidad.
        Set<UUID> colecciones = atributos.stream()
                .filter(UmlAttribute::isMultivalued)
                .filter(a -> decisiones.esOO(Ambiguedad.TipoAmbiguedad.MULTIVALUED, a.getId(),
                        Ambiguedad.Lectura.DATA))
                .map(UmlAttribute::getId)
                .collect(Collectors.toSet());

        return new Plan(List.copyOf(aplanadas), Set.copyOf(absorbidas),
                Map.copyOf(debiles), Set.copyOf(puentes), Set.copyOf(colecciones));
    }

    // ------------------------------------------------------------- COLUMNAS

    private static void construirColumnas(UmlClass c, List<UmlAttribute> propios, Borrador t,
                                          List<Borrador> extras, List<UmlRelationship> relaciones,
                                          Map<UUID, UmlClass> porId, List<String> advertencias,
                                          Plan plan, Map<UUID, List<UmlAttribute>> atributosPorClase,
                                          Map<String, ModeloLogico.Enumeracion> enums) {
        List<UmlAttribute> ordenados = propios.stream()
                .sorted(Comparator.comparingInt(UmlAttribute::getOrdinal)).toList();

        // REGLA 11: la entidad debil no lleva surrogado; su PK es la del
        // propietario mas la clave parcial.
        //
        // Una clase puede ser debil porque el usuario la marco, o porque leyo
        // una composicion como "entidad debil" en el punto de ambiguedad A-1.
        UmlRelationship porDecision = plan.debilesPorDecision.get(c.getId());
        List<UmlRelationship> identificadoras = porDecision != null
                ? List.of(porDecision)
                : (esDebil(c) ? identificadorasDe(c, relaciones) : List.of());
        UmlRelationship identificadora = identificadoras.isEmpty() ? null : identificadoras.get(0);
        UmlAttribute identidadTecnica = identificadora == null
                ? ordenados.stream().filter(a -> esIdentidadTecnica(c, a)).findFirst().orElse(null)
                : null;
        if (porDecision != null && ordenados.stream().noneMatch(UmlAttribute::isPartialKey)) {
            advertencias.add(c.getName() + " se leyo como entidad DEBIL, pero no tiene ningun"
                    + " atributo marcado como clave parcial: su clave primaria queda siendo solo la de"
                    + " " + nombre(porId, otroLadoId(porDecision, c.getId()))
                    + ", asi que no puede tener dos filas por duenio (Regla 11).");
        }
        if (esDebil(c) && identificadora == null) {
            advertencias.add("La entidad debil " + c.getName()
                    + " no tiene relacion identificadora: es obligatoria (Regla 11).");
        }

        if (identificadora != null) {
            // UNA columna por cada identificadora, y todas en la PK. Con dos
            // --la clase asociativa de una N:M-- eso es exactamente la tabla
            // puente de siempre, pero con sus atributos propios adentro.
            for (UmlRelationship r : identificadoras) {
                UmlClass duenio = otroLado(r, c.getId(), porId);
                if (duenio == null) continue;
                String tablaDuenio = Nombres.tabla(duenio.getName());
                // La debil no sobrevive a su duenio: la FK es parte de su PK.
                t.agregar(new ModeloLogico.Columna(Nombres.columnaFk(tablaDuenio), "uuid",
                        true, false, false, tablaDuenio + "(id)", "CASCADE",
                        r.getId()), advertencias);
            }
        } else {
            // REGLA 1: una identidad UUID declarada en UML representa la misma
            // columna fisica `id`; no se materializa una segunda columna
            // `id_entidad`. Si no fue declarada, el surrogado sigue siendo
            // automatico. En ambos casos las FK pueden apuntar establemente a
            // `<tabla>(id)`.
            ModeloLogico.Columna identidad = ModeloLogico.Columna.simple(
                    "id", "uuid", true, false, false);
            if (identidadTecnica != null) identidad = identidad.delAtributo(identidadTecnica.getId());
            t.agregar(identidad, advertencias);
        }

        for (UmlAttribute a : ordenados) {
            if (a == identidadTecnica) continue;
            if (a.isDerived()) {
                advertencias.add("El atributo derivado " + c.getName() + "." + a.getName()
                        + " no se persiste: se calcula (Regla 10).");
                continue;
            }
            if (a.isMultivalued()) {
                boolean comoColeccion = plan.colecciones.contains(a.getId());
                extras.add(tablaDeMultivaluado(c, a, t.nombre, comoColeccion, enums));
                advertencias.add("El atributo multivaluado " + c.getName() + "." + a.getName()
                        + " se extrajo a su propia tabla: es 1FN, no una opcion (Regla 12)."
                        + (comoColeccion
                           ? " Se leyo como @ElementCollection: cuelga de " + c.getName()
                             + ", sin entidad propia (punto de ambiguedad A-3)."
                           : ""));
                continue;
            }
            UmlClass valor = claseIncrustable(a.getDataType(), porId);
            if (valor != null) {
                incrustar(c, t, a.getName(), valor, a.isRequired(), atributosPorClase, porId, enums,
                        advertencias);
                continue;
            }
            if (TipoSql.esDesconocido(a.getDataType()) && !enums.containsKey(a.getDataType())) {
                advertencias.add("Tipo desconocido \"" + a.getDataType() + "\" en " + c.getName() + "."
                        + a.getName() + ": se mapeo a varchar.");
            }

            boolean parcialDeDebil = identificadora != null && a.isPartialKey();
            boolean claveNatural = a.isPk() && !parcialDeDebil;
            if (claveNatural) {
                advertencias.add("El atributo " + c.getName() + "." + a.getName()
                        + " quedo como clave natural UNIQUE NOT NULL; la PK es el id surrogado (Regla 1).");
            }
            t.agregar(columnaDe(Nombres.columna(a.getName()), a, parcialDeDebil,
                    !(a.isRequired() || claveNatural || parcialDeDebil),
                    a.isUnique() || claveNatural, enums), advertencias);
        }

        // A-2 leido como OO: las partes @Embeddable no tienen tabla, sus
        // columnas viven aca, prefijadas para no chocar con las del duenio.
        for (Aplanado aplanado : plan.aplanadas) {
            if (!aplanado.todoId().equals(c.getId())) continue;
            UmlClass parte = porId.get(aplanado.parteId());
            if (parte == null) continue;
            // Composicion 1..1: la parte es obligatoria para el todo.
            incrustar(c, t, aplanado.prefijo(), parte, true, atributosPorClase, porId, enums,
                    advertencias);
            advertencias.add(parte.getName() + " se aplano dentro de " + Nombres.tabla(c.getName())
                    + " como @Embeddable: dejo de tener tabla propia"
                    + (Incrustables.es(parte) ? " (es «embeddable», Regla 5)." : " (punto de ambiguedad A-2)."));
        }
    }

    /**
     * Un UUID llamado `id`, `idClase` o `id_clase` es identidad tecnica, no
     * una clave natural adicional. Se conserva su trazabilidad sobre la
     * columna fisica canonica `id`.
     */
    private static boolean esIdentidadTecnica(UmlClass clase, UmlAttribute atributo) {
        if (!atributo.isPk() || !"uuid".equalsIgnoreCase(atributo.getDataType())) return false;
        String nombre = atributo.getName() == null ? "" : atributo.getName().replaceAll("[^A-Za-z0-9]", "");
        String claseLimpia = clase.getName() == null ? "" : clase.getName().replaceAll("[^A-Za-z0-9]", "");
        return nombre.equalsIgnoreCase("id") || nombre.equalsIgnoreCase("id" + claseLimpia);
    }

    /** La clase «embeddable» con ese nombre, o null. */
    private static UmlClass claseIncrustable(String nombre, Map<UUID, UmlClass> porId) {
        if (nombre == null) return null;
        return porId.values().stream().filter(Incrustables::es)
                .filter(x -> x.getName().equals(nombre)).findFirst().orElse(null);
    }

    /**
     * REGLA 5: los atributos del valor, como columnas del duenio prefijadas con
     * el nombre del campo (`direccion_calle`). Se registra que columna es de que
     * atributo: el generador lo necesita para armar el @Embeddable.
     */
    private static void incrustar(UmlClass duenio, Borrador t, String campo, UmlClass valor,
                                  boolean obligatorio, Map<UUID, List<UmlAttribute>> atributosPorClase,
                                  Map<UUID, UmlClass> porId, Map<String, ModeloLogico.Enumeracion> enums,
                                  List<String> advertencias) {
        String prefijo = Nombres.columna(campo);
        List<ModeloLogico.ColumnaIncrustada> columnas = new ArrayList<>();
        for (UmlAttribute a : atributosPorClase.getOrDefault(valor.getId(), List.of()).stream()
                .sorted(Comparator.comparingInt(UmlAttribute::getOrdinal)).toList()) {
            if (a.isDerived()) continue;
            if (a.isMultivalued()) {
                advertencias.add("El atributo multivaluado " + valor.getName() + "." + a.getName()
                        + " no se puede guardar dentro de " + duenio.getName()
                        + ": un @Embeddable no lleva colecciones (Regla 10).");
                continue;
            }
            if (claseIncrustable(a.getDataType(), porId) != null) {
                advertencias.add(valor.getName() + "." + a.getName() + " es otro valor embebido: anidar"
                        + " @Embeddable no esta soportado en esta version y se omite (Regla 10).");
                continue;
            }
            // Un campo solo es NOT NULL si el valor es obligatorio en el duenio
            // Y el campo es obligatorio en el valor.
            String nombre = t.agregar(columnaDe(prefijo + "_" + Nombres.columna(a.getName()), a,
                    false, !(obligatorio && a.isRequired()), a.isUnique(), enums), advertencias);
            columnas.add(new ModeloLogico.ColumnaIncrustada(a.getName(), nombre));
        }
        if (columnas.isEmpty()) {
            advertencias.add(valor.getName() + " no tiene atributos que guardar: " + duenio.getName() + "."
                    + campo + " no genera columnas (Regla 10).");
            return;
        }
        t.incrustados.add(new ModeloLogico.Incrustado(campo, valor.getName(), obligatorio, List.copyOf(columnas)));
    }

    /** REGLA 12: el multivaluado sale a una tabla propia con FK. */
    private static Borrador tablaDeMultivaluado(UmlClass c, UmlAttribute a, String tablaDuenio,
                                                boolean comoColeccion,
                                                Map<String, ModeloLogico.Enumeracion> enums) {
        Borrador t = new Borrador(tablaDuenio + "_" + Nombres.columna(a.getName()),
                c.getName() + "." + a.getName()
                        + (comoColeccion ? " (@ElementCollection)" : " (multivaluado)"));
        t.columnas.add(new ModeloLogico.Columna(Nombres.columnaFk(tablaDuenio), "uuid", true, false, false,
                tablaDuenio + "(id)", "CASCADE", null));
        t.columnas.add(columnaDe(Nombres.columna(a.getName()), a, true, false, false, enums));
        return t;
    }

    /**
     * La columna de un atributo: su tipo sale de la tabla de tipos, salvo que el
     * tipo sea una enumeracion del modelo, que da `varchar` con sus literales.
     */
    private static ModeloLogico.Columna columnaDe(String nombreColumna, UmlAttribute a, boolean pk,
                                                  boolean nullable, boolean unique,
                                                  Map<String, ModeloLogico.Enumeracion> enums) {
        ModeloLogico.Enumeracion e = enums.get(a.getDataType());
        if (e != null) {
            return ModeloLogico.Columna.simple(nombreColumna, Enumeraciones.tipoSql(e), pk, nullable, unique)
                    .conEnumeracion(e).delAtributo(a.getId());
        }
        ModeloLogico.Columna c = ModeloLogico.Columna.simple(nombreColumna,
                TipoSql.de(a.getDataType(), a.getLength()), pk, nullable, unique).delAtributo(a.getId());
        // Regla 9: el rango ya se valido al entrar al bus (numerico, min <= max).
        if (a.getMinValue() == null && a.getMaxValue() == null) return c;
        String tipo = a.getDataType() == null ? "" : a.getDataType().toLowerCase();
        boolean entero = tipo.equals("int") || tipo.equals("integer") || tipo.equals("long");
        if (!entero && !tipo.equals("decimal")) return c;
        return c.conRango(new ModeloLogico.Rango(a.getMinValue(), a.getMaxValue(), entero));
    }

    // ----------------------------------------------------------- RELACIONES

    /** REGLA 4: JOINED. La hija comparte la PK con la madre, y la abstracta TAMBIEN genera tabla. */
    private static void aplicarHerencia(UmlRelationship r, Map<UUID, UmlClass> porId,
                                        Map<UUID, Borrador> tablas, List<String> advertencias) {
        if (r.getSourceClassId().equals(r.getTargetClassId())) {
            advertencias.add("La clase " + nombre(porId, r.getSourceClassId())
                    + " no puede heredar de si misma: la generalizacion se ignora (Regla 10).");
            return;
        }

        Borrador hija = tablas.get(r.getSourceClassId());
        Borrador madre = tablas.get(r.getTargetClassId());
        if (hija == null || madre == null) return;

        ModeloLogico.Columna id = hija.columnas.get(0);
        if (id.referencia() != null) {
            advertencias.add("Herencia multiple en " + nombre(porId, r.getSourceClassId())
                    + ": no es mapeable (Regla 4/10).");
            return;
        }
        hija.columnas.set(0, new ModeloLogico.Columna(id.nombre(), id.tipo(), true, false, false,
                madre.nombre + "(id)", "CASCADE", r.getId()));
    }

    private static void aplicarAsociacion(UmlRelationship r, Map<UUID, UmlClass> porId,
                                          Map<UUID, Borrador> tablas, List<Borrador> extras,
                                          List<String> advertencias, Plan plan) {
        UmlClass origen = porId.get(r.getSourceClassId());
        UmlClass destino = porId.get(r.getTargetClassId());
        boolean origenMuchos = esMuchos(r.getSourceMultiplicity());
        boolean destinoMuchos = esMuchos(r.getTargetMultiplicity());

        if (r.getSourceMultiplicity() == null && r.getTargetMultiplicity() == null) {
            advertencias.add("La relacion entre " + origen.getName() + " y " + destino.getName()
                    + " no declara multiplicidades: se asumio 1:N (Regla 3).");
            destinoMuchos = true;
        }

        if (origenMuchos && destinoMuchos) {
            extras.add(tablaPuente(tablas.get(origen.getId()), tablas.get(destino.getId()), r,
                    origen.getName() + " N:M " + destino.getName(), advertencias,
                    plan.puentesConId.contains(r.getId())));
            return;
        }

        // El lado "muchos" es el propietario de la FK; en 1:1 la lleva el destino.
        boolean fkEnDestino = destinoMuchos || !origenMuchos;
        Borrador tabla = fkEnDestino ? tablas.get(destino.getId()) : tablas.get(origen.getId());
        UmlClass apuntada = fkEnDestino ? origen : destino;
        String rol = fkEnDestino ? r.getSourceRole() : r.getTargetRole();
        boolean unoAUno = !origenMuchos && !destinoMuchos;

        if (tabla == null) return;
        boolean nullable = !esObligatoria(
                fkEnDestino ? r.getSourceMultiplicity() : r.getTargetMultiplicity());

        // La composicion obliga: la parte no existe sin el todo, sea cual sea
        // la multiplicidad que hayan dibujado.
        if (r.getKind() == RelationKind.COMPOSITION && nullable) {
            advertencias.add("La composicion " + origen.getName() + " - " + destino.getName()
                    + " fuerza la FK a NOT NULL: la parte no existe sin el todo.");
            nullable = false;
        }

        tabla.agregar(ModeloLogico.Columna.fk(
                Nombres.columnaFk(rol != null && !rol.isBlank() ? rol : apuntada.getName()),
                nullable,
                unoAUno,
                Nombres.tabla(apuntada.getName()) + "(id)",
                reglaDeBorrado(r.getKind(), nullable, origen, destino, advertencias),
                r.getId()).haciaExtremo(fkEnDestino ? "SOURCE" : "TARGET"), advertencias);
    }

    /**
     * Lo que distingue a una composicion de una agregacion en el esquema. Sin
     * esto las dos generan la misma FK y la diferencia que dibujo el usuario
     * no llega a ningun lado.
     */
    private static String reglaDeBorrado(RelationKind kind, boolean nullable,
                                         UmlClass origen, UmlClass destino,
                                         List<String> advertencias) {
        return switch (kind) {
            // La parte muere con el todo.
            case COMPOSITION -> "CASCADE";
            // La parte sobrevive: se le suelta la referencia. Si la FK es
            // obligatoria eso es imposible, y conviene decirlo.
            case AGGREGATION -> {
                if (nullable) yield "SET NULL";
                advertencias.add("La agregacion " + origen.getName() + " - " + destino.getName()
                        + " tiene la FK obligatoria, asi que no puede ser SET NULL: queda RESTRICT."
                        + " Si la parte deberia sobrevivir, poné el extremo en 0..1 o 0..*.");
                yield "RESTRICT";
            }
            default -> "RESTRICT";
        };
    }

    /**
     * REGLA 3: N:M -> tabla puente. Las columnas salen de las CLASES, que es
     * lo legible en el caso normal.
     *
     * Salvo en una relacion REFLEXIVA: ahi las dos clases son la misma y los
     * dos nombres saldrian iguales, o sea una tabla con dos columnas del mismo
     * nombre que no compila. En ese caso mandan los roles, que es lo unico que
     * distingue un extremo del otro.
     */
    private static Borrador tablaPuente(Borrador a, Borrador b, UmlRelationship r,
                                        String origen, List<String> advertencias,
                                        boolean conIdSurrogado) {
        boolean reflexiva = r.getSourceClassId().equals(r.getTargetClassId());
        String colA = Nombres.columnaFk(reflexiva ? rolO(r.getSourceRole(), a.nombre) : a.nombre);
        String colB = Nombres.columnaFk(reflexiva ? rolO(r.getTargetRole(), b.nombre) : b.nombre);

        String nombreTabla = reflexiva
                ? a.nombre + "_" + Nombres.identificador(rolO(r.getTargetRole(), "rel"))
                : a.nombre + "_" + b.nombre;

        Borrador t = new Borrador(nombreTabla, origen
                + (conIdSurrogado ? " (clase asociativa)" : ""));

        // A-4 leido como OO: la tabla puente pasa a ser una entidad con su
        // propio id. Es lo que le permite al generador emitirle las cinco
        // capas, porque su regla es "una sola PK y que se llame id".
        if (conIdSurrogado) {
            t.agregar(ModeloLogico.Columna.simple("id", "uuid", true, false, false), advertencias);
            advertencias.add("La tabla " + nombreTabla + " se leyo como clase asociativa: lleva id"
                    + " surrogado y genera Entity. Agregale un UNIQUE(" + colA + ", " + colB + ")"
                    + " si no querias permitir el mismo par dos veces (punto de ambiguedad A-4).");
        }

        // Si desaparece cualquiera de los dos lados, la fila del puente no
        // significa nada: se va con el.
        t.agregar(new ModeloLogico.Columna(colA, "uuid", !conIdSurrogado, false, false,
                a.nombre + "(id)", "CASCADE", r.getId()), advertencias);
        // Por `agregar`: si los roles tampoco alcanzan, desambigua y avisa.
        t.agregar(new ModeloLogico.Columna(colB, "uuid", !conIdSurrogado, false, false,
                b.nombre + "(id)", "CASCADE", r.getId()), advertencias);
        return t;
    }

    private static String rolO(String rol, String porDefecto) {
        return rol != null && !rol.isBlank() ? rol : porDefecto;
    }

    // -------------------------------------------------------------- APOYO

    private static boolean esDebil(UmlClass c) {
        return c.isWeak() || c.getErKind() == ErKind.WEAK;
    }

    /**
     * TODAS las relaciones identificadoras de una clase debil, no la primera.
     *
     * Que sean varias es el caso normal, no el raro: una clase asociativa --el
     * "Vende" entre NotaVenta y Producto-- es debil respecto de las DOS clases que
     * une, y su clave primaria son las dos claves foraneas juntas. Mientras esto
     * devolvia solo la primera, la segunda relacion se saltaba por
     * {@link #esIdentificadoraDeDebil} dandola por materializada y su FK no se
     * emitia en ningun lado: el esquema salia sin esa columna, sin esa foranea y
     * SIN UN AVISO. Era la peor variante posible --una relacion que el usuario
     * dibujo y que desaparece en silencio-- y justo lo que la Regla 10 prohibe.
     */
    private static List<UmlRelationship> identificadorasDe(UmlClass c,
                                                           List<UmlRelationship> relaciones) {
        return relaciones.stream()
                .filter(UmlRelationship::isIdentifying)
                .filter(r -> r.getSourceClassId().equals(c.getId()) || r.getTargetClassId().equals(c.getId()))
                .toList();
    }

    /** La identificadora ya se materializo como PK compuesta: no vuelve a poner FK. */
    private static boolean esIdentificadoraDeDebil(UmlRelationship r, Map<UUID, UmlClass> porId,
                                                   Plan plan) {
        // La composicion leida como entidad debil ya puso la FK dentro de la
        // PK compuesta: si volviera a pasar por aca, saldria dos veces.
        if (plan.debilesPorDecision.values().stream().anyMatch(x -> x.getId().equals(r.getId()))) {
            return true;
        }
        if (!r.isIdentifying()) return false;
        UmlClass origen = porId.get(r.getSourceClassId());
        UmlClass destino = porId.get(r.getTargetClassId());
        return (origen != null && esDebil(origen)) || (destino != null && esDebil(destino));
    }

    private static UmlClass otroLado(UmlRelationship r, UUID esteId, Map<UUID, UmlClass> porId) {
        return porId.get(otroLadoId(r, esteId));
    }

    private static UUID otroLadoId(UmlRelationship r, UUID esteId) {
        return r.getSourceClassId().equals(esteId) ? r.getTargetClassId() : r.getSourceClassId();
    }

    private static boolean esMuchos(String multiplicidad) {
        return multiplicidad != null && multiplicidad.contains("*");
    }

    /** "1" y "1..*" obligan; "0..1" y "0..*" no. */
    private static boolean esObligatoria(String multiplicidad) {
        return multiplicidad != null && multiplicidad.startsWith("1");
    }

    private static String nombre(Map<UUID, UmlClass> porId, UUID id) {
        UmlClass c = porId.get(id);
        return c == null ? "?" : c.getName();
    }

    private static final class Borrador {
        private final String nombre;
        private String origen;
        /** La clase que la produjo; null en una tabla puente o de multivaluado. */
        private final UUID claseId;
        private final List<ModeloLogico.Columna> columnas = new ArrayList<>();
        private ModeloLogico.TablaUnica tablaUnica;
        private final List<ModeloLogico.Incrustado> incrustados = new ArrayList<>();

        Borrador(String nombre, String origen) {
            this(nombre, origen, null);
        }

        Borrador(String nombre, String origen, UUID claseId) {
            this.nombre = nombre;
            this.origen = origen;
            this.claseId = claseId;
        }

        /**
         * Dos columnas con el mismo nombre no compilan. Pasa cuando hay dos
         * relaciones entre las mismas clases sin roles (modelo canonico 07):
         * se desambigua y se avisa, en vez de emitir un esquema roto.
         */
        /** @return el nombre con el que quedo la columna, que puede no ser el pedido. */
        String agregar(ModeloLogico.Columna c, List<String> advertencias) {
            if (columnas.stream().noneMatch(x -> x.nombre().equals(c.nombre()))) {
                columnas.add(c);
                return c.nombre();
            }
            advertencias.add("La tabla " + nombre + " genero dos columnas \"" + c.nombre()
                    + "\": ponele ROLES a las relaciones para distinguirlas (Regla 3).");
            int i = 2;
            String candidato;
            do {
                candidato = c.nombre() + "_" + i++;
            } while (columnasTiene(candidato));
            // Con la enumeracion incluida: perderla aca dejaba la columna
            // desambiguada sin su CHECK.
            columnas.add(c.como(candidato, c.pk(), c.nullable()));
            return candidato;
        }

        private boolean columnasTiene(String n) {
            return columnas.stream().anyMatch(x -> x.nombre().equals(n));
        }

        ModeloLogico.Tabla aTabla() {
            return new ModeloLogico.Tabla(nombre, origen, List.copyOf(columnas), tablaUnica,
                    List.copyOf(incrustados), claseId);
        }
    }
}
