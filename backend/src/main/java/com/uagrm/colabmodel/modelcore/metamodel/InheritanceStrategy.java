package com.uagrm.colabmodel.modelcore.metamodel;

/**
 * Como se mapea una jerarquia de herencia (Regla 4). Se fija en la clase raiz.
 *
 * JOINED (por defecto): una tabla por clase, la hija comparte la PK con la
 * madre. Normalizado, sin columnas nulas forzadas; cuesta un JOIN por nivel.
 *
 * SINGLE_TABLE: una sola tabla para toda la jerarquia, con una columna
 * discriminadora. Sin JOINs, pero las columnas de cada subclase tienen que
 * admitir NULL, porque las filas de las demas subclases no las tienen.
 */
public enum InheritanceStrategy { JOINED, SINGLE_TABLE }
