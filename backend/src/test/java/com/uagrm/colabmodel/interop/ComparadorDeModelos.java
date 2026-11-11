package com.uagrm.colabmodel.interop;

import com.uagrm.colabmodel.interop.ModeloLeido.AtributoLeido;
import com.uagrm.colabmodel.interop.ModeloLeido.ClaseLeida;
import com.uagrm.colabmodel.interop.ModeloLeido.MetodoLeido;
import com.uagrm.colabmodel.interop.ModeloLeido.RelacionLeida;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * M-QA-05 · comparador semantico de dos modelos leidos (E08).
 *
 * Recibe dos {@link ModeloLeido} (esperado, obtenido) y lista las diferencias en clases, atributos, metodos y
 * relaciones. Compara por NOMBRE (hoy el nombre cualificado es el nombre simple: los paquetes se aplanan; cuando el
 * contrato admita paquetes (U06) el unico lugar a cambiar es {@link #claveDeClase}) y por propiedades.
 *
 * Ignora a proposito: el orden de clases, atributos, metodos y relaciones; las claves internas (xmi:id) salvo que se
 * pida {@link Opciones#comparaIdentidad}; los espacios sobrantes; la forma de escribir una multiplicidad
 * (`1` = `1..1`, `*` = `0..*`); y el texto de las advertencias, que describe al lector y no al modelo.
 *
 * NO ignora nada mas: visibilidad, valor inicial, tipo (con su mayuscula), PK, derivado, estereotipo, abstracta,
 * retorno, tipo de relacion, roles, multiplicidades y el sentido de la relacion. Un `null` ("el archivo no lo dice")
 * y un valor concreto son diferentes: si un viaje de ida y vuelta convierte `null` en `PRIVATE`, se reporta.
 *
 * Cuando el contrato v1 agregue campos a {@code ModeloLeido} (static, readOnly, direction, navegabilidad,
 * externalId...), se suman aqui en {@link #comparaAtributo}, {@link #comparaMetodo} y {@link #comparaRelacion}.
 */
final class ComparadorDeModelos {

    enum Tipo { ELEMENTO_FALTANTE, ELEMENTO_SOBRANTE, PROPIEDAD_DISTINTA, IDENTIDAD_DISTINTA }

    /**
     * @param ruta       `Clase`, `Clase.atributo`, `Clase.metodo(int,String)` o `Origen -> Destino TIPO`
     * @param propiedad  nombre de la propiedad que difiere; null si falta o sobra el elemento entero
     * @param esperado   valor del primer modelo (null = no lo tiene)
     * @param obtenido   valor del segundo modelo
     */
    record Diferencia(Tipo tipo, String ruta, String propiedad, String esperado, String obtenido) {
        @Override
        public String toString() {
            return switch (tipo) {
                case ELEMENTO_FALTANTE -> "FALTA     " + ruta;
                case ELEMENTO_SOBRANTE -> "SOBRA     " + ruta;
                case PROPIEDAD_DISTINTA -> "DISTINTO  " + ruta + " · " + propiedad + ": esperado=" + esperado
                        + " obtenido=" + obtenido;
                case IDENTIDAD_DISTINTA -> "IDENTIDAD " + ruta + ": esperado=" + esperado + " obtenido=" + obtenido;
            };
        }
    }

    /**
     * @param comparaIdentidad si es true, la `clave` (xmi:id / GUID) de cada clase debe coincidir. Sirve para
     *                         EA -> ColabModel -> EA, donde el GUID tiene que sobrevivir; entre archivos de
     *                         distinta herramienta se deja en false.
     * @param posiciones       si es true, compara el diagrama (x, y, ancho, alto) con la tolerancia dada
     */
    record Opciones(boolean comparaIdentidad, boolean posiciones, double tolerancia) {
        static final Opciones SEMANTICA = new Opciones(false, false, 0);
        static final Opciones CON_IDENTIDAD = new Opciones(true, false, 0);

        Opciones conPosiciones(double tolerancia) {
            return new Opciones(comparaIdentidad, true, tolerancia);
        }
    }

    record Resultado(List<Diferencia> diferencias) {
        boolean iguales() {
            return diferencias.isEmpty();
        }

        List<Diferencia> deTipo(Tipo t) {
            return diferencias.stream().filter(d -> d.tipo() == t).toList();
        }

        /** Informe legible para pegar en la ficha. */
        String comoTexto() {
            if (diferencias.isEmpty()) return "Sin diferencias.";
            return diferencias.size() + " diferencia(s):\n"
                    + diferencias.stream().map(Diferencia::toString).collect(Collectors.joining("\n"));
        }
    }

    private final Opciones opciones;
    private final List<Diferencia> out = new ArrayList<>();

    private ComparadorDeModelos(Opciones opciones) {
        this.opciones = opciones;
    }

    static Resultado comparar(ModeloLeido esperado, ModeloLeido obtenido) {
        return comparar(esperado, obtenido, Opciones.SEMANTICA);
    }

    static Resultado comparar(ModeloLeido esperado, ModeloLeido obtenido, Opciones opciones) {
        ComparadorDeModelos c = new ComparadorDeModelos(opciones);
        c.clases(esperado, obtenido);
        c.relaciones(esperado, obtenido);
        return new Resultado(List.copyOf(c.out));
    }

    // ================================================================ clases

    /** El nombre cualificado de una clase. Hoy es el nombre simple (los paquetes se aplanan al leer). */
    static String claveDeClase(ClaseLeida c) {
        return norm(c.nombre());
    }

    private void clases(ModeloLeido e, ModeloLeido o) {
        Map<String, ClaseLeida> ce = indexar(e.clases());
        Map<String, ClaseLeida> co = indexar(o.clases());
        for (String k : union(ce.keySet(), co.keySet())) {
            ClaseLeida a = ce.get(k);
            ClaseLeida b = co.get(k);
            if (b == null) { faltante(k); continue; }
            if (a == null) { sobrante(k); continue; }
            propiedad(k, "estereotipo", norm(a.estereotipo()), norm(b.estereotipo()));
            propiedad(k, "abstracta", a.abstracta(), b.abstracta());
            if (opciones.comparaIdentidad() && !Objects.equals(a.clave(), b.clave())) {
                out.add(new Diferencia(Tipo.IDENTIDAD_DISTINTA, k, "clave", a.clave(), b.clave()));
            }
            atributos(k, a, b);
            metodos(k, a, b);
            if (opciones.posiciones()) posicion(k, e, a, o, b);
        }
    }

    private static Map<String, ClaseLeida> indexar(List<ClaseLeida> clases) {
        Map<String, ClaseLeida> m = new LinkedHashMap<>();
        for (ClaseLeida c : clases) m.putIfAbsent(claveDeClase(c), c);
        return m;
    }

    private void posicion(String ruta, ModeloLeido e, ClaseLeida a, ModeloLeido o, ClaseLeida b) {
        ModeloLeido.Posicion pa = e.posiciones().get(a.clave());
        ModeloLeido.Posicion pb = o.posiciones().get(b.clave());
        if (pa == null && pb == null) return;
        if (pa == null || pb == null) {
            propiedad(ruta, "posicion", pa == null ? null : pa.toString(), pb == null ? null : pb.toString());
            return;
        }
        double t = opciones.tolerancia();
        if (Math.abs(pa.x() - pb.x()) > t || Math.abs(pa.y() - pb.y()) > t
                || Math.abs(pa.ancho() - pb.ancho()) > t || Math.abs(pa.alto() - pb.alto()) > t) {
            propiedad(ruta, "posicion", pa.toString(), pb.toString());
        }
    }

    // ============================================================= atributos

    private void atributos(String clase, ClaseLeida a, ClaseLeida b) {
        Map<String, AtributoLeido> ae = new LinkedHashMap<>();
        Map<String, AtributoLeido> ao = new LinkedHashMap<>();
        a.atributos().forEach(x -> ae.putIfAbsent(norm(x.nombre()), x));
        b.atributos().forEach(x -> ao.putIfAbsent(norm(x.nombre()), x));
        for (String k : union(ae.keySet(), ao.keySet())) {
            String ruta = clase + "." + k;
            if (!ao.containsKey(k)) { faltante(ruta); continue; }
            if (!ae.containsKey(k)) { sobrante(ruta); continue; }
            comparaAtributo(ruta, ae.get(k), ao.get(k));
        }
    }

    private void comparaAtributo(String ruta, AtributoLeido a, AtributoLeido b) {
        propiedad(ruta, "tipo", norm(a.tipoLeido()), norm(b.tipoLeido()));
        propiedad(ruta, "pk", a.pk(), b.pk());
        propiedad(ruta, "requerido", a.requerido(), b.requerido());
        propiedad(ruta, "unico", a.unico(), b.unico());
        propiedad(ruta, "multivaluado", a.multivaluado(), b.multivaluado());
        propiedad(ruta, "derivado", a.derivado(), b.derivado());
        propiedad(ruta, "claveParcial", a.claveParcial(), b.claveParcial());
        propiedad(ruta, "visibilidad", norm(a.visibilidad()), norm(b.visibilidad()));
        propiedad(ruta, "valorPorDefecto", norm(a.valorPorDefecto()), norm(b.valorPorDefecto()));
    }

    // =============================================================== metodos

    /** Un metodo se identifica por su firma: nombre y tipos de los parametros (como `CommandApplier.firmaDe`). */
    static String firma(MetodoLeido m) {
        return norm(m.nombre()) + "(" + m.parametros().stream().map(p -> norm(p.tipo()))
                .collect(Collectors.joining(",")) + ")";
    }

    private void metodos(String clase, ClaseLeida a, ClaseLeida b) {
        Map<String, MetodoLeido> me = new LinkedHashMap<>();
        Map<String, MetodoLeido> mo = new LinkedHashMap<>();
        a.metodos().forEach(x -> me.putIfAbsent(firma(x), x));
        b.metodos().forEach(x -> mo.putIfAbsent(firma(x), x));
        for (String k : union(me.keySet(), mo.keySet())) {
            String ruta = clase + "." + k;
            if (!mo.containsKey(k)) { faltante(ruta); continue; }
            if (!me.containsKey(k)) { sobrante(ruta); continue; }
            comparaMetodo(ruta, me.get(k), mo.get(k));
        }
    }

    private void comparaMetodo(String ruta, MetodoLeido a, MetodoLeido b) {
        propiedad(ruta, "retorno", norm(a.tipoRetorno()), norm(b.tipoRetorno()));
        propiedad(ruta, "visibilidad", norm(a.visibilidad()), norm(b.visibilidad()));
        propiedad(ruta, "estatico", a.esEstatico(), b.esEstatico());
        propiedad(ruta, "abstracto", a.esAbstracto(), b.esAbstracto());
        // Los tipos ya forman la firma; los NOMBRES de parametro y su orden si cuentan.
        for (int i = 0; i < Math.min(a.parametros().size(), b.parametros().size()); i++) {
            propiedad(ruta, "parametro[" + i + "].nombre", norm(a.parametros().get(i).nombre()),
                    norm(b.parametros().get(i).nombre()));
        }
    }

    // ============================================================ relaciones

    /** Grupo de relaciones entre el mismo par de clases y del mismo tipo; dentro se empareja por nombre y roles. */
    private static String grupo(ModeloLeido m, RelacionLeida r) {
        return nombreDe(m, r.claveOrigen()) + " -> " + nombreDe(m, r.claveDestino()) + " " + r.tipo();
    }

    private static String nombreDe(ModeloLeido m, String clave) {
        return m.clases().stream().filter(c -> Objects.equals(c.clave(), clave)).map(ComparadorDeModelos::claveDeClase)
                .findFirst().orElse("?" + clave);
    }

    private static String detalle(RelacionLeida r) {
        return norm(r.nombre()) + "|" + norm(r.rolOrigen()) + "|" + norm(r.rolDestino()) + "|"
                + multiplicidad(r.multiplicidadOrigen()) + "|" + multiplicidad(r.multiplicidadDestino());
    }

    private void relaciones(ModeloLeido e, ModeloLeido o) {
        Map<String, List<RelacionLeida>> ge = agrupar(e);
        Map<String, List<RelacionLeida>> go = agrupar(o);
        for (String k : union(ge.keySet(), go.keySet())) {
            List<RelacionLeida> pe = new ArrayList<>(ge.getOrDefault(k, List.of()));
            List<RelacionLeida> po = new ArrayList<>(go.getOrDefault(k, List.of()));
            // 1) las identicas se emparejan primero; 2) lo que queda se empareja en orden y se comparan propiedades.
            for (var it = pe.iterator(); it.hasNext(); ) {
                RelacionLeida r = it.next();
                RelacionLeida igual = po.stream().filter(x -> detalle(x).equals(detalle(r))).findFirst().orElse(null);
                if (igual != null) { po.remove(igual); it.remove(); }
            }
            int n = Math.min(pe.size(), po.size());
            for (int i = 0; i < n; i++) comparaRelacion(k, pe.get(i), po.get(i));
            for (int i = n; i < pe.size(); i++) faltante(k + " [" + detalle(pe.get(i)) + "]");
            for (int i = n; i < po.size(); i++) sobrante(k + " [" + detalle(po.get(i)) + "]");
        }
    }

    private Map<String, List<RelacionLeida>> agrupar(ModeloLeido m) {
        Map<String, List<RelacionLeida>> g = new LinkedHashMap<>();
        for (RelacionLeida r : m.relaciones()) g.computeIfAbsent(grupo(m, r), x -> new ArrayList<>()).add(r);
        return g;
    }

    private void comparaRelacion(String ruta, RelacionLeida a, RelacionLeida b) {
        propiedad(ruta, "nombre", norm(a.nombre()), norm(b.nombre()));
        propiedad(ruta, "rolOrigen", norm(a.rolOrigen()), norm(b.rolOrigen()));
        propiedad(ruta, "rolDestino", norm(a.rolDestino()), norm(b.rolDestino()));
        propiedad(ruta, "multiplicidadOrigen", multiplicidad(a.multiplicidadOrigen()), multiplicidad(b.multiplicidadOrigen()));
        propiedad(ruta, "multiplicidadDestino", multiplicidad(a.multiplicidadDestino()), multiplicidad(b.multiplicidadDestino()));
        propiedad(ruta, "identificadora", a.identificadora(), b.identificadora());
    }

    // ============================================================== utiles

    private void faltante(String ruta) {
        out.add(new Diferencia(Tipo.ELEMENTO_FALTANTE, ruta, null, ruta, null));
    }

    private void sobrante(String ruta) {
        out.add(new Diferencia(Tipo.ELEMENTO_SOBRANTE, ruta, null, null, ruta));
    }

    private void propiedad(String ruta, String nombre, Object esperado, Object obtenido) {
        if (!Objects.equals(esperado, obtenido)) {
            out.add(new Diferencia(Tipo.PROPIEDAD_DISTINTA, ruta, nombre,
                    esperado == null ? null : esperado.toString(), obtenido == null ? null : obtenido.toString()));
        }
    }

    private static Set<String> union(Set<String> a, Set<String> b) {
        Set<String> r = new LinkedHashSet<>(a);
        r.addAll(b);
        return r;
    }

    /** Sin espacios sobrantes; vacio es lo mismo que null. */
    static String norm(String s) {
        if (s == null) return null;
        String t = s.trim().replaceAll("\\s+", " ");
        return t.isEmpty() ? null : t;
    }

    /** `1` = `1..1`, `*` = `0..*`, `n` = `n..n`; sin multiplicidad queda null. */
    static String multiplicidad(String m) {
        String t = norm(m);
        if (t == null) return null;
        t = t.replace(" ", "");
        if (t.equals("*")) return "0..*";
        if (!t.contains("..")) return t + ".." + t;
        return t;
    }
}
