package com.uagrm.colabmodel.codegen;

import com.uagrm.colabmodel.modelcore.mapping.Nombres;

import java.util.Set;

/**
 * Nombres Java a partir de los del modelo conceptual.
 *
 * Parte de la misma normalizacion que la Regla 8 (transliteracion y
 * snake_case) pero esquiva las palabras reservadas de JAVA, que no son las de
 * PostgreSQL: `user` es un nombre de campo perfectamente valido en Java,
 * aunque su columna tenga que llamarse `user_`.
 */
public final class NombresJava {

    private static final Set<String> RESERVADAS = Set.of(
            "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class",
            "const", "continue", "default", "do", "double", "else", "enum", "extends", "final",
            "finally", "float", "for", "goto", "if", "implements", "import", "instanceof", "int",
            "interface", "long", "native", "new", "package", "private", "protected", "public",
            "return", "short", "static", "strictfp", "super", "switch", "synchronized", "this",
            "throw", "throws", "transient", "try", "void", "volatile", "while",
            "true", "false", "null", "record", "var", "yield");

    private NombresJava() { }

    /** "Año Académico" -> "AnioAcademico" */
    public static String clase(String nombre) {
        String[] partes = Nombres.normalizado(nombre).split("_");
        StringBuilder sb = new StringBuilder();
        for (String p : partes) {
            if (!p.isEmpty()) sb.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1));
        }
        return sb.isEmpty() ? "SinNombre" : sb.toString();
    }

    /** "nombre_completo" -> "nombreCompleto"; "class" -> "class_" */
    public static String campo(String nombre) {
        String pascal = clase(nombre);
        String camel = Character.toLowerCase(pascal.charAt(0)) + pascal.substring(1);
        return RESERVADAS.contains(camel) ? camel + "_" : camel;
    }

    /** "nombreCompleto" -> "getNombreCompleto" */
    public static String getter(String campo) {
        return "get" + Character.toUpperCase(campo.charAt(0)) + campo.substring(1);
    }

    public static String setter(String campo) {
        return "set" + Character.toUpperCase(campo.charAt(0)) + campo.substring(1);
    }
}
