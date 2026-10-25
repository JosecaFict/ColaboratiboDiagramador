package com.uagrm.colabmodel.ai;

import java.util.List;

/**
 * Lo que se le manda al modelo: el pedido del usuario, el estado actual del
 * diagrama (para que no invente clases que ya existen ni las duplique) y las
 * herramientas que puede llamar (EsquemaDeComandos, derivado del catalogo).
 */
public record SolicitudIA(String prompt, ModoIA modo, List<ClaseEnContexto> clases,
                          List<RelacionEnContexto> relaciones, List<HerramientaIA> herramientas,
                          String modelo) {

    public SolicitudIA(String prompt, ModoIA modo, List<ClaseEnContexto> clases,
                       List<RelacionEnContexto> relaciones, List<HerramientaIA> herramientas) {
        this(prompt, modo, clases, relaciones, herramientas, null);
    }

    /** Conserva el contrato usado por proveedores y pruebas anteriores. */
    public SolicitudIA(String prompt, List<ClaseEnContexto> clases, List<HerramientaIA> herramientas) {
        this(prompt, ModoIA.MODIFICAR, clases, List.of(), herramientas, null);
    }

    public record ClaseEnContexto(String nombre, List<String> atributos, List<String> metodos,
                                  String estereotipo, boolean abstracta) {
        public ClaseEnContexto(String nombre, List<String> atributos) {
            this(nombre, atributos, List.of(), null, false);
        }
    }

    public record RelacionEnContexto(String origen, String destino, String tipo, String nombre,
                                     String multiplicidadOrigen, String multiplicidadDestino) { }

    public String instruccionDeSistema() {
        return PromptDeSistema.para(modo);
    }

    /** El mensaje de usuario que efectivamente se le manda al modelo. */
    public String mensajeParaElModelo() {
        StringBuilder sb = new StringBuilder(prompt);
        if (!clases.isEmpty()) {
            sb.append("\n\nClases existentes en el diagrama:\n");
            for (ClaseEnContexto c : clases) {
                sb.append("- ").append(c.nombre());
                if (!c.atributos().isEmpty()) {
                    sb.append(" (").append(String.join(", ", c.atributos())).append(')');
                }
                if (!c.metodos().isEmpty()) sb.append("; metodos: ").append(String.join(", ", c.metodos()));
                if (c.estereotipo() != null) sb.append("; estereotipo: ").append(c.estereotipo());
                if (c.abstracta()) sb.append("; abstracta");
                sb.append('\n');
            }
        }
        if (!relaciones.isEmpty()) {
            sb.append("\nRelaciones existentes:\n");
            for (RelacionEnContexto r : relaciones) {
                sb.append("- ").append(r.origen()).append(" [").append(r.multiplicidadOrigen()).append("] --")
                        .append(r.tipo()).append("--> ").append(r.destino()).append(" [")
                        .append(r.multiplicidadDestino()).append("]");
                if (r.nombre() != null) sb.append(" : ").append(r.nombre());
                sb.append('\n');
                sb.append("  Lectura exacta: cada ").append(r.origen()).append(" puede relacionarse con ")
                        .append(r.multiplicidadDestino()).append(" instancia(s) de ").append(r.destino())
                        .append("; cada ").append(r.destino()).append(" puede relacionarse con ")
                        .append(r.multiplicidadOrigen()).append(" instancia(s) de ").append(r.origen())
                        .append(". No inviertas esta lectura.\n");
            }
        }
        return sb.toString();
    }
}
