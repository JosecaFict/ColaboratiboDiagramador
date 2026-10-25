package com.uagrm.colabmodel.interop;

import com.uagrm.colabmodel.modelcore.mapping.InferenciaDeAtributos;
import com.uagrm.colabmodel.modelcore.metamodel.UmlAttribute;
import com.uagrm.colabmodel.modelcore.metamodel.UmlAttributeRepository;
import com.uagrm.colabmodel.modelcore.metamodel.UmlClass;
import com.uagrm.colabmodel.modelcore.metamodel.UmlClassRepository;
import com.uagrm.colabmodel.modelcore.metamodel.UmlMethod;
import com.uagrm.colabmodel.modelcore.metamodel.UmlMethodRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Convierte un archivo en una propuesta revisable.
 *
 * Tres pasos, siempre los mismos sin importar el formato:
 *   1. el lector que corresponda lo lee (ModeloLeido);
 *   2. se cruza con el diagrama: que clases ya existen, que atributos ya estan;
 *   3. cada atributo recibe su sugerencia de tipo y rol.
 *
 * Lectura pura: la escritura llega despues, como BATCH, si el usuario confirma.
 */
@Service
public class ServicioDeImportacion {

    private final List<LectorDeModelo> lectores;
    private final UmlClassRepository classes;
    private final UmlAttributeRepository attributes;
    private final UmlMethodRepository methods;

    public ServicioDeImportacion(List<LectorDeModelo> lectores, UmlClassRepository classes,
                                 UmlAttributeRepository attributes, UmlMethodRepository methods) {
        this.lectores = lectores;
        this.classes = classes;
        this.attributes = attributes;
        this.methods = methods;
    }

    /** Los formatos que se pueden importar hoy, para decirlo cuando llega otro. */
    public List<String> formatosSoportados() {
        return lectores.stream().map(LectorDeModelo::formato).toList();
    }

    @Transactional(readOnly = true)
    public PropuestaDeImportacion proponer(UUID diagramId, String nombreArchivo, byte[] contenido) {
        LectorDeModelo lector = lectores.stream().filter(l -> l.acepta(nombreArchivo, contenido)).findFirst()
                .orElseThrow(() -> new FormatoNoSoportadoException("No se puede importar \"" + nombreArchivo
                        + "\". Formatos soportados hoy: " + String.join(", ", formatosSoportados())));
        ModeloLeido leido = lector.leer(contenido);
        return cruzarConElDiagrama(diagramId, nombreArchivo, leido);
    }

    PropuestaDeImportacion cruzarConElDiagrama(UUID diagramId, String fuente, ModeloLeido leido) {
        List<UmlClass> existentes = classes.findByDiagramIdAndDeletedAtIsNullOrderByNameAsc(diagramId);
        Map<String, UmlClass> existentePorNombre = existentes.stream()
                .collect(Collectors.toMap(c -> c.getName().toLowerCase(Locale.ROOT), c -> c, (a, b) -> a));
        Map<UUID, Set<String>> atributosExistentes = new HashMap<>();
        Map<UUID, Set<String>> metodosExistentes = new HashMap<>();
        if (!existentes.isEmpty()) {
            List<UUID> ids = existentes.stream().map(UmlClass::getId).toList();
            for (UmlAttribute a : attributes.findByClassIdInOrderByOrdinalAsc(ids)) {
                atributosExistentes.computeIfAbsent(a.getClassId(), k -> new HashSet<>())
                        .add(a.getName().toLowerCase(Locale.ROOT));
            }
            for (UmlMethod m : methods.findByClassIdInOrderByOrdinalAsc(ids)) {
                metodosExistentes.computeIfAbsent(m.getClassId(), k -> new HashSet<>())
                        .add(m.getName().toLowerCase(Locale.ROOT));
            }
        }

        // Para sugerir, las clases que "existen" son las del diagrama MAS las
        // del archivo: idProveedor es una FK si Proveedor viene en el mismo modelo.
        Map<String, UmlClass> figuraDe = new LinkedHashMap<>();
        List<UmlClass> universo = new ArrayList<>(existentes);
        for (ModeloLeido.ClaseLeida c : leido.clases()) {
            UmlClass yaEsta = existentePorNombre.get(c.nombre().toLowerCase(Locale.ROOT));
            if (yaEsta != null) {
                figuraDe.put(c.clave(), yaEsta);
                continue;
            }
            UmlClass figura = new UmlClass();
            figura.setId(UUID.randomUUID());
            figura.setDiagramId(diagramId);
            figura.setName(c.nombre());
            figura.setStereotype(c.estereotipo());
            figuraDe.put(c.clave(), figura);
            universo.add(figura);
        }

        List<String> advertencias = new ArrayList<>(leido.advertencias());
        List<PropuestaDeImportacion.ClasePropuesta> clases = new ArrayList<>();
        for (ModeloLeido.ClaseLeida c : leido.clases()) {
            UmlClass figura = figuraDe.get(c.clave());
            boolean existe = existentePorNombre.containsKey(c.nombre().toLowerCase(Locale.ROOT));
            Set<String> yaTiene = existe ? atributosExistentes.getOrDefault(figura.getId(), Set.of()) : Set.of();
            Set<String> yaTieneMetodo = existe ? metodosExistentes.getOrDefault(figura.getId(), Set.of()) : Set.of();

            List<PropuestaDeImportacion.AtributoPropuesto> atributos = new ArrayList<>();
            for (ModeloLeido.AtributoLeido a : c.atributos()) {
                InferenciaDeAtributos.Sugerencia s = InferenciaDeAtributos.sugerir(a.nombre(), a.tipoLeido(),
                        figura, universo);
                // La PK sale del archivo; que parezca clave natural es una sugerencia
                // que el usuario ve y decide, no algo que se marque solo.
                atributos.add(new PropuestaDeImportacion.AtributoPropuesto(a.nombre(), a.tipoLeido(), s.dataType(),
                        a.pk(), a.requerido(), a.unico(), a.multivaluado(), a.derivado(), a.claveParcial(),
                        yaTiene.contains(a.nombre().toLowerCase(Locale.ROOT)), s));
            }
            List<PropuestaDeImportacion.MetodoPropuesto> metodosPropuestos = new ArrayList<>();
            for (ModeloLeido.MetodoLeido m : c.metodos()) {
                metodosPropuestos.add(new PropuestaDeImportacion.MetodoPropuesto(m.nombre(), m.tipoRetorno(),
                        m.parametros(), yaTieneMetodo.contains(m.nombre().toLowerCase(Locale.ROOT))));
            }
            clases.add(new PropuestaDeImportacion.ClasePropuesta(c.clave(), figura.getId(), c.nombre(),
                    c.estereotipo(), c.abstracta(), existe ? figura.getId() : null, List.copyOf(atributos),
                    List.copyOf(metodosPropuestos), leido.posiciones().get(c.clave())));
        }

        long repetidas = clases.stream().filter(c -> c.idExistente() != null).count();
        if (repetidas > 0) {
            advertencias.add(repetidas + (repetidas == 1 ? " clase ya existe" : " clases ya existen")
                    + " en el diagrama: no se crean de nuevo, se les agregan los atributos que les falten.");
        }

        List<PropuestaDeImportacion.RelacionPropuesta> relaciones = leido.relaciones().stream()
                .map(r -> new PropuestaDeImportacion.RelacionPropuesta(r.claveOrigen(), r.claveDestino(), r.tipo(),
                        r.nombre(), r.multiplicidadOrigen(), r.multiplicidadDestino(), r.rolOrigen(), r.rolDestino(),
                        r.identificadora()))
                .toList();

        return new PropuestaDeImportacion(leido.formato(), fuente, List.copyOf(clases), relaciones,
                List.copyOf(advertencias));
    }
}
