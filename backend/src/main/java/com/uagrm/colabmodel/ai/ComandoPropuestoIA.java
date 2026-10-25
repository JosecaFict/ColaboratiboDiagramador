package com.uagrm.colabmodel.ai;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * UNA llamada a herramienta, tal cual la devolvio el modelo. Sin validar:
 * `tipo` puede no existir en CommandType y `parametros` puede faltarle
 * cualquier campo. Es la contraparte, en el mundo de la IA, de lo que
 * `ModeloLeido` es para un archivo importado.
 */
public record ComandoPropuestoIA(String tipo, JsonNode parametros) { }
