package com.uagrm.colabmodel.modelcore.metamodel;

/**
 * Las cuatro proyecciones del metamodelo canonico (ADR-003).
 * CROWSFOOT se agrega despues del parcial: sera una implementacion mas de
 * NotationRenderer, sin tocar el bus, los locks ni el generador (ADR-015).
 */
public enum NotationView { CLASS, CHEN, LOGICAL, PHYSICAL }
