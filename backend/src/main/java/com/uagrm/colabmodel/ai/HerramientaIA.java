package com.uagrm.colabmodel.ai;

import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * El function-calling schema de UN comando, en la forma que entiende
 * cualquier proveedor (Claude lo llama "tool", Ollama "function").
 *
 * @param nombre     el mismo texto que CommandType.name(): es como el modelo
 *                   dice "quiero este comando"
 * @param parametros JSON Schema del payload, en refs legibles (nombres de
 *                   clase) en vez de UUID: el modelo no conoce los UUID del
 *                   diagrama, ResolutorDeReferencias los resuelve despues
 */
public record HerramientaIA(String nombre, String descripcion, ObjectNode parametros) { }
