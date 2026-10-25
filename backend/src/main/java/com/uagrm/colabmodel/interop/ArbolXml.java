package com.uagrm.colabmodel.interop;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.ByteArrayInputStream;
import java.io.StringReader;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Un XML leido con StAX a un arbol propio, tolerante (ADR-019).
 *
 * Tres decisiones que no son obvias:
 *   - SIN namespaces. `uml:Class`, `UML:Class` y un `xmi:type="uml:Class"`
 *     con el prefijo declarado distinto son lo mismo para nosotros: se compara
 *     por nombre local. Un prefijo sin declarar, que con namespaces tumba el
 *     parser en la primera linea, aca no importa.
 *   - SIN DTD ni entidades externas: un XMI es un archivo que sube un usuario
 *     (XXE).
 *   - ROTO NO ES VACIO. Si el XML se corta o esta mal formado, primero se
 *     reintenta saneado (entidades HTML sueltas, `&` sin escapar, caracteres
 *     de control: lo que suele romper las notas de un export de EA) y, si
 *     igual falla, se conserva lo leido hasta el error y se dice donde.
 */
final class ArbolXml {

    private ArbolXml() { }

    static final class Nodo {
        final String nombre;
        final String local;
        final Map<String, String> atributos = new LinkedHashMap<>();
        final List<Nodo> hijos = new ArrayList<>();
        final Nodo padre;
        final int linea;
        private final StringBuilder texto = new StringBuilder();

        Nodo(String nombre, Nodo padre, int linea) {
            this.nombre = nombre;
            this.local = local(nombre);
            this.padre = padre;
            this.linea = linea;
        }

        /**
         * Atributo por nombre normalizado: `xmi:id` y `xmi.id` se guardan como
         * `xmi:id`; cualquier otro prefijo se quita (`uml:name` -> `name`).
         */
        String atr(String nombre) {
            return atributos.get(nombre);
        }

        String texto() {
            return texto.toString().trim();
        }

        String id() {
            String id = atr("xmi:id");
            return id != null ? id : atr("id");
        }

        String idref() {
            return atr("xmi:idref");
        }

        /** El tipo del elemento: el de `xmi:type` si lo tiene, si no su nombre local. */
        String clase() {
            String t = atr("xmi:type");
            return t != null ? local(t) : local;
        }

        boolean es(String... clases) {
            String c = clase();
            for (String x : clases) if (c.equalsIgnoreCase(x) || local.equalsIgnoreCase(x)) return true;
            return false;
        }

        List<Nodo> hijos(String... locales) {
            List<Nodo> r = new ArrayList<>();
            for (Nodo h : hijos) if (h.esLocal(locales)) r.add(h);
            return r;
        }

        Optional<Nodo> hijo(String... locales) {
            for (Nodo h : hijos) if (h.esLocal(locales)) return Optional.of(h);
            return Optional.empty();
        }

        boolean esLocal(String... locales) {
            for (String l : locales) if (local.equalsIgnoreCase(l)) return true;
            return false;
        }

        void recorrer(java.util.function.Consumer<Nodo> accion) {
            Deque<Nodo> pila = new ArrayDeque<>(List.of(this));
            while (!pila.isEmpty()) {
                Nodo n = pila.pop();
                accion.accept(n);
                for (int i = n.hijos.size() - 1; i >= 0; i--) pila.push(n.hijos.get(i));
            }
        }

        static String local(String nombre) {
            int dos = nombre.lastIndexOf(':');
            return dos >= 0 ? nombre.substring(dos + 1) : nombre;
        }
    }

    /**
     * @param error    null si el XML se leyo entero
     * @param saneado  hizo falta corregir el texto para leerlo: el archivo tenia algo mal y se avisa
     * @param original el error del XML tal cual vino, si hizo falta sanear
     */
    record Resultado(Nodo raiz, int elementos, String error, boolean saneado, String original) {
        Resultado(Nodo raiz, int elementos, String error) {
            this(raiz, elementos, error, false, null);
        }
    }

    static Resultado leer(byte[] contenido) {
        Resultado directo = intentar(() -> fabrica().createXMLStreamReader(new ByteArrayInputStream(contenido)));
        if (directo.error() == null) return directo;

        String saneado = sanear(decodificar(contenido));
        Resultado reintento = intentar(() -> fabrica().createXMLStreamReader(new StringReader(saneado)));
        if (reintento.error() == null) {
            return new Resultado(reintento.raiz(), reintento.elementos(), null, true, directo.error());
        }
        return reintento.elementos() > directo.elementos()
                ? new Resultado(reintento.raiz(), reintento.elementos(), reintento.error(), true, directo.error())
                : directo;
    }

    private interface Apertura {
        XMLStreamReader abrir() throws XMLStreamException;
    }

    private static Resultado intentar(Apertura apertura) {
        Nodo documento = new Nodo("#documento", null, 0);
        Nodo actual = documento;
        int elementos = 0;
        XMLStreamReader r = null;
        try {
            r = apertura.abrir();
            while (r.hasNext()) {
                int evento = r.next();
                if (evento == XMLStreamConstants.START_ELEMENT) {
                    Nodo n = new Nodo(nombreDe(r.getPrefix(), r.getLocalName()), actual, r.getLocation().getLineNumber());
                    for (int i = 0; i < r.getAttributeCount(); i++) {
                        guardarAtributo(n, nombreDe(r.getAttributePrefix(i), r.getAttributeLocalName(i)),
                                r.getAttributeValue(i));
                    }
                    actual.hijos.add(n);
                    actual = n;
                    elementos++;
                } else if (evento == XMLStreamConstants.END_ELEMENT) {
                    if (actual.padre != null) actual = actual.padre;
                } else if (evento == XMLStreamConstants.CHARACTERS || evento == XMLStreamConstants.CDATA) {
                    if (actual != documento && actual.texto.length() < 20000) actual.texto.append(r.getText());
                }
            }
            return new Resultado(documento, elementos, null);
        } catch (XMLStreamException | RuntimeException e) {
            String donde = e instanceof XMLStreamException xe && xe.getLocation() != null
                    ? "linea " + xe.getLocation().getLineNumber() + ", columna " + xe.getLocation().getColumnNumber()
                    : "cerca del elemento " + elementos;
            return new Resultado(documento, elementos, donde + ": " + primeraFrase(e.getMessage()));
        } finally {
            if (r != null) {
                try {
                    r.close();
                } catch (XMLStreamException ignorada) {
                    // cerrar un lector que ya fallo no cambia nada
                }
            }
        }
    }

    private static String nombreDe(String prefijo, String local) {
        return prefijo == null || prefijo.isEmpty() ? local : prefijo + ":" + local;
    }

    private static void guardarAtributo(Nodo n, String nombre, String valor) {
        String clave;
        String lower = nombre.toLowerCase(Locale.ROOT);
        if (lower.startsWith("xmi:") || lower.startsWith("xmi.")) {
            clave = "xmi:" + nombre.substring(4);
        } else {
            clave = Nodo.local(nombre);
        }
        // Un atributo xmi:* manda sobre uno sin prefijo con el mismo nombre.
        if (clave.startsWith("xmi:") || !n.atributos.containsKey(clave)) n.atributos.put(clave, valor);
    }

    private static XMLInputFactory fabrica() {
        XMLInputFactory f = XMLInputFactory.newFactory();
        f.setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, false);
        f.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        f.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        f.setProperty(XMLInputFactory.IS_COALESCING, true);
        return f;
    }

    /**
     * UTF-8 si lo es; si no, windows-1252, que es lo que declara EA. Se quita
     * la declaracion XML: despues de decodificar, su `encoding` ya no aplica.
     */
    private static String decodificar(byte[] contenido) {
        String texto;
        try {
            texto = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(contenido)).toString();
        } catch (CharacterCodingException e) {
            texto = new String(contenido, Charset.forName("windows-1252"));
        }
        if (texto.startsWith("﻿")) texto = texto.substring(1);
        return texto.replaceFirst("^\\s*<\\?xml[^?]*\\?>", "");
    }

    private static final Pattern ENTIDAD = Pattern.compile("&(#x[0-9a-fA-F]+|#[0-9]+|[A-Za-z][A-Za-z0-9]*);?");
    private static final Set<String> PREDEFINIDAS = Set.of("amp", "lt", "gt", "quot", "apos");

    static String sanear(String xml) {
        StringBuilder sb = new StringBuilder(xml.length());
        Matcher m = ENTIDAD.matcher(xml);
        int ultimo = 0;
        while (m.find()) {
            sb.append(xml, ultimo, m.start());
            String cuerpo = m.group(1);
            boolean cerrada = m.group().endsWith(";");
            if (cerrada && (cuerpo.startsWith("#") || PREDEFINIDAS.contains(cuerpo))) {
                sb.append(m.group());
            } else {
                // `&nbsp;` de una nota en HTML, o un `&` suelto: se escapa como texto.
                sb.append("&amp;").append(m.group(), 1, m.group().length());
            }
            ultimo = m.end();
        }
        sb.append(xml.substring(ultimo));
        // Caracteres de control que XML 1.0 no admite ni escapados.
        return sb.toString().replaceAll("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]", " ")
                .replaceAll("&(?![A-Za-z#])", "&amp;");
    }

    private static String primeraFrase(String mensaje) {
        if (mensaje == null) return "XML mal formado";
        String sinPosicion = mensaje.replaceAll("(?s)^ParseError at \\[row,col\\]:\\[\\d+,\\d+\\]\\s*Message:\\s*", "");
        int punto = sinPosicion.indexOf(". ");
        return (punto > 0 ? sinPosicion.substring(0, punto) : sinPosicion).trim();
    }
}
