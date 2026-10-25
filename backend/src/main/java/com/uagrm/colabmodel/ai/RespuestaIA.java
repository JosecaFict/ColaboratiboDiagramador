package com.uagrm.colabmodel.ai;

import java.util.List;

/**
 * @param comandos     las llamadas a herramienta que hizo el modelo, en el
 *                     orden en que las hizo (el orden importa: una clase se
 *                     tiene que crear antes de que otro cambio la referencie)
 * @param notaDelModelo texto libre que el modelo haya agregado ademas de las
 *                     llamadas a herramienta. Se muestra al usuario tal cual,
 *                     nunca se interpreta como instruccion
 */
public record RespuestaIA(List<ComandoPropuestoIA> comandos, String notaDelModelo) { }
