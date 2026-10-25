package com.uagrm.colabmodel.interop;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.uagrm.colabmodel.modelcore.mapping.InferenciaDeAtributos;
import com.uagrm.colabmodel.modelcore.mapping.ModeloCanonico;
import com.uagrm.colabmodel.modelcore.metamodel.*;

import java.util.*;

/**
 * Lo que queda en el modelo si el usuario aprieta "Aplicar" sin tocar nada,
 * sobre un diagrama vacio.
 *
 * Es espejo de `revisionDe` + `planDeImportacion` en
 * frontend/src/importacion/propuesta.ts (cada atributo entra salvo la
 * identidad; un atributo con rol RELACION entra como relacion 0..* -> 1), y
 * de lo que CommandApplier hace con esos comandos. La prueba de ida y vuelta
 * tiene que pasar por la MISMA propuesta que ve el usuario: si no, estaria
 * probando un camino que nadie usa.
 */
final class RevisionPorDefecto {

    private RevisionPorDefecto() { }

    static ModeloCanonico.Cargado aplicar(PropuestaDeImportacion p) {
        UUID diagrama = UUID.randomUUID();
        Map<String, UUID> idDeClave = new HashMap<>();
        List<UmlClass> clases = new ArrayList<>();
        for (PropuestaDeImportacion.ClasePropuesta c : p.clases()) {
            idDeClave.put(c.clave(), c.id());
            UmlClass u = new UmlClass();
            u.setId(c.id());
            u.setDiagramId(diagrama);
            u.setName(c.nombre().trim());
            u.setStereotype(c.estereotipo());
            u.setAbstract(c.abstracta());
            u.setErKind(ErKind.STRONG);
            clases.add(u);
        }

        List<UmlRelationship> relaciones = new ArrayList<>();
        Set<String> pares = new HashSet<>();
        for (PropuestaDeImportacion.RelacionPropuesta r : p.relaciones()) {
            UUID origen = idDeClave.get(r.claveOrigen());
            UUID destino = idDeClave.get(r.claveDestino());
            if (origen == null || destino == null) continue;
            pares.add(par(origen, destino));
            relaciones.add(relacion(diagrama, origen, destino, r.tipo(), r.nombre(), r.multiplicidadOrigen(),
                    r.multiplicidadDestino(), r.rolOrigen(), r.rolDestino(), r.identificadora()));
        }

        List<UmlAttribute> atributos = new ArrayList<>();
        for (PropuestaDeImportacion.ClasePropuesta c : p.clases()) {
            UUID idClase = idDeClave.get(c.clave());
            int ordinal = 0;
            for (PropuestaDeImportacion.AtributoPropuesto a : c.atributos()) {
                InferenciaDeAtributos.Sugerencia s = a.sugerencia();
                if (a.yaExiste() || s.rol() == InferenciaDeAtributos.Rol.IDENTIDAD) continue;
                if (s.rol() == InferenciaDeAtributos.Rol.RELACION && s.claseRelacionadaId() != null) {
                    if (pares.add(par(idClase, s.claseRelacionadaId()))) {
                        relaciones.add(relacion(diagrama, idClase, s.claseRelacionadaId(), RelationKind.ASSOCIATION,
                                null, "0..*", "1", null, null, false));
                    }
                    continue;
                }
                UmlAttribute u = new UmlAttribute();
                u.setId(UUID.randomUUID());
                u.setClassId(idClase);
                u.setName(a.nombre().trim());
                u.setDataType(a.tipo());
                u.setPk(a.pk());
                u.setRequired(a.requerido());
                u.setUnique(a.unico());
                u.setMultivalued(a.multivaluado());
                u.setDerived(a.derivado());
                u.setPartialKey(a.claveParcial());
                u.setOrdinal(ordinal++);
                atributos.add(u);
            }
        }

        List<UmlMethod> metodos = new ArrayList<>();
        for (PropuestaDeImportacion.ClasePropuesta c : p.clases()) {
            UUID idClase = idDeClave.get(c.clave());
            int ordinal = 0;
            for (PropuestaDeImportacion.MetodoPropuesto m : c.metodos()) {
                if (m.yaExiste()) continue;
                UmlMethod u = new UmlMethod();
                u.setId(UUID.randomUUID());
                u.setClassId(idClase);
                u.setName(m.nombre().trim());
                u.setReturnType(m.tipoRetorno() == null ? "void" : m.tipoRetorno());
                u.setParams(paramsJson(m.parametros()));
                u.setOrdinal(ordinal++);
                metodos.add(u);
            }
        }
        return new ModeloCanonico.Cargado(clases, atributos, relaciones, metodos);
    }

    private static String paramsJson(List<ModeloLeido.ParametroLeido> parametros) {
        ArrayNode arr = new ObjectMapper().createArrayNode();
        for (ModeloLeido.ParametroLeido p : parametros) {
            ObjectNode o = arr.addObject();
            o.put("name", p.nombre());
            o.put("type", p.tipo() == null ? "string" : p.tipo());
        }
        return arr.toString();
    }

    private static UmlRelationship relacion(UUID diagrama, UUID origen, UUID destino, RelationKind tipo, String nombre,
                                            String multOrigen, String multDestino, String rolOrigen, String rolDestino,
                                            boolean identificadora) {
        UmlRelationship r = new UmlRelationship();
        r.setId(UUID.randomUUID());
        r.setDiagramId(diagrama);
        r.setSourceClassId(origen);
        r.setTargetClassId(destino);
        r.setKind(tipo);
        r.setName(nombre);
        r.setSourceMultiplicity(multOrigen);
        r.setTargetMultiplicity(multDestino);
        r.setSourceRole(rolOrigen);
        r.setTargetRole(rolDestino);
        r.setIdentifying(identificadora);
        return r;
    }

    private static String par(UUID a, UUID b) {
        return a.compareTo(b) <= 0 ? a + "|" + b : b + "|" + a;
    }
}
