package com.uagrm.colabmodel.interop;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uagrm.colabmodel.modelcore.metamodel.RelationKind;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.*;

/**
 * El JSON de `canonical-models/`: el formato de intercambio del propio proyecto.
 *
 * Es el primer lector a proposito. Los 7 modelos canonicos son archivos reales
 * con los casos dificiles (herencia, N:M, reflexiva, nombres con acentos), asi
 * que la pantalla de revision se prueba contra modelos de verdad antes de que
 * existan los lectores de XMI y SQL.
 */
@Component
public class LectorModeloJson implements LectorDeModelo {

    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String formato() { return "JSON"; }

    /**
     * El mensaje de Jackson trae detalles internos ("[Source: REDACTED ...]")
     * que al usuario no le sirven. Se traducen los casos comunes y del resto
     * se deja solo la primera frase.
     */
    static String explicar(String original) {
        if (original == null) return "formato no reconocido";
        if (original.startsWith("Unexpected end-of-input")) {
            return "el archivo termina antes de cerrar todas las llaves { } o corchetes [ ]";
        }
        if (original.startsWith("Unexpected character")) {
            return "hay un caracter fuera de lugar (una coma de mas, o comillas simples en vez de dobles)";
        }
        return original.replaceAll("\\s*\\(start marker.*$", "").replaceAll("\\s*at \\[Source.*$", "");
    }

    @Override
    public boolean acepta(String nombreArchivo, byte[] contenido) {
        return nombreArchivo != null && nombreArchivo.toLowerCase(Locale.ROOT).endsWith(".json");
    }

    @Override
    public ModeloLeido leer(byte[] contenido) {
        JsonNode raiz;
        try {
            raiz = mapper.readTree(contenido);
        } catch (JsonProcessingException e) {
            throw new ArchivoInvalidoException("El archivo no es un JSON valido (linea "
                    + e.getLocation().getLineNr() + ", columna " + e.getLocation().getColumnNr() + "): "
                    + explicar(e.getOriginalMessage()), e);
        } catch (IOException e) {
            throw new ArchivoInvalidoException("No se pudo leer el archivo", e);
        }
        if (raiz == null || !raiz.path("clases").isArray()) {
            throw new ArchivoInvalidoException("El JSON no tiene una lista \"clases\": no parece un modelo"
                    + " de ColabModel (ver canonical-models/)");
        }

        List<String> advertencias = new ArrayList<>();
        List<ModeloLeido.ClaseLeida> clases = new ArrayList<>();
        Set<String> claves = new HashSet<>();
        Set<String> nombres = new HashSet<>();

        int n = 1;
        for (JsonNode c : raiz.path("clases")) {
            String nombre = c.path("name").asText("").trim();
            String clave = c.path("id").asText(nombre);
            if (nombre.isEmpty()) {
                advertencias.add("La clase " + n + " no tiene nombre: se omitio.");
                n++;
                continue;
            }
            // Dos clases con el mismo nombre no pueden convivir en un diagrama.
            if (!nombres.add(nombre.toLowerCase(Locale.ROOT))) {
                advertencias.add("La clase \"" + nombre + "\" esta repetida en el archivo: se tomo la primera.");
                n++;
                continue;
            }
            claves.add(clave);

            List<ModeloLeido.AtributoLeido> atributos = new ArrayList<>();
            for (JsonNode a : c.path("attributes")) {
                String nombreAtributo = a.path("name").asText("").trim();
                if (nombreAtributo.isEmpty()) {
                    advertencias.add("Un atributo de " + nombre + " no tiene nombre: se omitio.");
                    continue;
                }
                atributos.add(new ModeloLeido.AtributoLeido(nombreAtributo,
                        a.hasNonNull("dataType") ? a.get("dataType").asText() : null,
                        a.path("pk").asBoolean(false), a.path("required").asBoolean(false),
                        a.path("unique").asBoolean(false), a.path("multivalued").asBoolean(false),
                        a.path("derived").asBoolean(false), a.path("partialKey").asBoolean(false)));
            }
            clases.add(new ModeloLeido.ClaseLeida(clave, nombre,
                    c.hasNonNull("stereotype") ? c.get("stereotype").asText() : null,
                    c.path("isAbstract").asBoolean(false), List.copyOf(atributos), List.of()));
            n++;
        }

        List<ModeloLeido.RelacionLeida> relaciones = new ArrayList<>();
        for (JsonNode r : raiz.path("relaciones")) {
            String origen = r.path("sourceClassId").asText();
            String destino = r.path("targetClassId").asText();
            if (!claves.contains(origen) || !claves.contains(destino)) {
                advertencias.add("Una relacion apunta a una clase que no esta en el archivo (" + origen + " -> "
                        + destino + "): se omitio.");
                continue;
            }
            RelationKind tipo;
            try {
                tipo = RelationKind.valueOf(r.path("kind").asText("ASSOCIATION"));
            } catch (IllegalArgumentException e) {
                advertencias.add("Tipo de relacion desconocido \"" + r.path("kind").asText()
                        + "\": se tomo como asociacion.");
                tipo = RelationKind.ASSOCIATION;
            }
            relaciones.add(new ModeloLeido.RelacionLeida(origen, destino, tipo, texto(r, "name"),
                    texto(r, "sourceMultiplicity"), texto(r, "targetMultiplicity"),
                    texto(r, "sourceRole"), texto(r, "targetRole"), r.path("identifying").asBoolean(false)));
        }

        return new ModeloLeido(formato(), List.copyOf(clases), List.copyOf(relaciones), List.copyOf(advertencias));
    }

    private static String texto(JsonNode n, String campo) {
        return n.hasNonNull(campo) && !n.get(campo).asText().isBlank() ? n.get(campo).asText() : null;
    }
}
