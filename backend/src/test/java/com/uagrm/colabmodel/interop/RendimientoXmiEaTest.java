package com.uagrm.colabmodel.interop;

import com.uagrm.colabmodel.modelcore.metamodel.RelationKind;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T-05 (D-13): la eficiencia del import de Enterprise Architect, medida.
 *
 * Arma un export de EA con la misma forma que `ea16-xmi21-tienda.xmi` (modelo
 * UML, Extension con elementos, atributos y conectores, y un diagrama) pero
 * con cientos de clases, y mide cuanto tarda el lector. El limite es holgado
 * para no fallar en una maquina lenta: lo que importa es que crezca lineal y
 * no cuadratico. Los numeros reales quedan en la ficha T-05.
 */
class RendimientoXmiEaTest {

    private static final String[] TIPOS = {"String", "int", "double", "boolean", "Date", "long"};

    /** Un export de EA 2.1 con `clases` clases de 8 atributos y 2 operaciones, encadenadas. */
    static byte[] exportDeEa(int clases) {
        StringBuilder uml = new StringBuilder(clases * 4096);
        StringBuilder elementos = new StringBuilder(clases * 3072);
        StringBuilder conectores = new StringBuilder(clases * 1024);
        StringBuilder dibujo = new StringBuilder(clases * 160);
        for (int i = 0; i < clases; i++) {
            String id = idClase(i);
            uml.append("<packagedElement xmi:type=\"uml:Class\" xmi:id=\"").append(id).append("\" name=\"Clase")
                    .append(i).append("\" visibility=\"public\">\n");
            if (i > 0 && i % 10 == 0) {
                uml.append("<generalization xmi:type=\"uml:Generalization\" xmi:id=\"EAID_G").append(i)
                        .append("\" general=\"").append(idClase(i - 1)).append("\"/>\n");
            }
            elementos.append("<element xmi:idref=\"").append(id).append("\" xmi:type=\"uml:Class\" name=\"Clase")
                    .append(i).append("\" scope=\"public\"><model package=\"EAPK_RAIZ\" tpos=\"0\" ea_localid=\"")
                    .append(i).append("\" ea_eleType=\"element\"/><properties isSpecification=\"false\" sType=\"Class\""
                            + " nType=\"0\" scope=\"public\" isAbstract=\"false\"/><tags/><xrefs/><attributes>\n");
            for (int a = 0; a < 8; a++) {
                String tipo = TIPOS[(i + a) % TIPOS.length];
                String idA = "EAID_A" + i + "_" + a;
                uml.append("<ownedAttribute xmi:type=\"uml:Property\" xmi:id=\"").append(idA).append("\" name=\"atributo")
                        .append(a).append("\" visibility=\"private\" isStatic=\"false\" isReadOnly=\"false\""
                                + " isDerived=\"false\" isOrdered=\"false\" isUnique=\"true\" isDerivedUnion=\"false\">"
                                + "<lowerValue xmi:type=\"uml:LiteralInteger\" xmi:id=\"").append(idA)
                        .append("_lo\" value=\"1\"/><upperValue xmi:type=\"uml:LiteralInteger\" xmi:id=\"").append(idA)
                        .append("_hi\" value=\"1\"/><type xmi:idref=\"EAJava_").append(tipo).append("\"/></ownedAttribute>\n");
                elementos.append("<attribute xmi:idref=\"").append(idA).append("\" name=\"atributo").append(a)
                        .append("\" scope=\"Private\"><initial/><documentation/><model ea_localid=\"").append(a)
                        .append("\"/><properties type=\"").append(tipo).append("\" precision=\"0\" collection=\"false\""
                                + " length=\"0\" static=\"0\" duplicates=\"0\" changeability=\"changeable\"/>"
                                + "<coords ordered=\"0\" scale=\"0\"/><stereotype/><bounds lower=\"1\" upper=\"1\"/>"
                                + "<options/><style/><styleex value=\"volatile=0;\"/><tags/><xrefs/></attribute>\n");
            }
            for (int o = 0; o < 2; o++) {
                uml.append("<ownedOperation xmi:id=\"EAID_O").append(i).append('_').append(o).append("\" name=\"operacion")
                        .append(o).append("\" visibility=\"public\" concurrency=\"sequential\">"
                                + "<ownedParameter xmi:id=\"EAID_P").append(i).append('_').append(o)
                        .append("\" name=\"valor\" direction=\"in\" type=\"EAJava_int\"/>"
                                + "<ownedParameter xmi:id=\"EAID_RT").append(i).append('_').append(o)
                        .append("\" name=\"return\" direction=\"return\" type=\"EAJava_void\"/></ownedOperation>\n");
            }
            uml.append("</packagedElement>\n");
            elementos.append("</attributes></element>\n");
            dibujo.append("<element geometry=\"Left=").append((i % 20) * 200).append(";Top=").append((i / 20) * 150)
                    .append(";Right=").append((i % 20) * 200 + 170).append(";Bottom=").append((i / 20) * 150 + 120)
                    .append(";\" subject=\"").append(id).append("\" seqno=\"").append(i).append("\" style=\"DUID=0;\"/>\n");

            if (i > 0) {   // asociacion 0..* -> 1 con la clase anterior
                String r = "EAID_R" + i;
                uml.append("<packagedElement xmi:type=\"uml:Association\" xmi:id=\"").append(r).append("\" visibility=\"public\">"
                                + "<memberEnd xmi:idref=\"EAID_dst").append(i).append("\"/><memberEnd xmi:idref=\"EAID_src")
                        .append(i).append("\"/>")
                        .append(extremo("EAID_dst" + i, r, idClase(i - 1), "1", "1"))
                        .append(extremo("EAID_src" + i, r, id, "0", "-1"))
                        .append("</packagedElement>\n");
                conectores.append("<connector xmi:idref=\"").append(r).append("\"><source xmi:idref=\"").append(id)
                        .append("\"><model type=\"Class\" name=\"Clase").append(i).append("\"/><role visibility=\"Public\"/>"
                                + "<type multiplicity=\"0..*\" aggregation=\"none\"/></source><target xmi:idref=\"")
                        .append(idClase(i - 1)).append("\"><model type=\"Class\"/><role visibility=\"Public\"/>"
                                + "<type multiplicity=\"1\" aggregation=\"none\"/></target><model ea_localid=\"").append(i)
                        .append("\"/><properties ea_type=\"Association\" direction=\"Source -&gt; Destination\"/>"
                                + "<labels/><tags/></connector>\n");
            }
        }
        String xmi = "<?xml version=\"1.0\" encoding=\"windows-1252\"?>\n"
                + "<xmi:XMI xmi:version=\"2.1\" xmlns:uml=\"http://schema.omg.org/spec/UML/2.1\""
                + " xmlns:xmi=\"http://schema.omg.org/spec/XMI/2.1\">\n"
                + "<xmi:Documentation exporter=\"Enterprise Architect\" exporterVersion=\"6.5\"/>\n"
                + "<uml:Model xmi:type=\"uml:Model\" name=\"EA_Model\" visibility=\"public\">\n"
                + "<packagedElement xmi:type=\"uml:Package\" xmi:id=\"EAPK_RAIZ\" name=\"Grande\" visibility=\"public\">\n"
                + uml + "</packagedElement>\n</uml:Model>\n"
                + "<xmi:Extension extender=\"Enterprise Architect\" extenderID=\"6.5\">\n<elements>\n" + elementos
                + "</elements>\n<connectors>\n" + conectores + "</connectors>\n<diagrams><diagram xmi:id=\"EAID_D1\">"
                + "<model package=\"EAPK_RAIZ\" localID=\"1\" owner=\"EAPK_RAIZ\"/><properties name=\"Grande\""
                + " type=\"Logical\"/><elements>\n" + dibujo + "</elements></diagram></diagrams>\n"
                + "</xmi:Extension>\n</xmi:XMI>\n";
        return xmi.getBytes(StandardCharsets.ISO_8859_1);
    }

    private static String idClase(int i) {
        return String.format("EAID_C%08d_0000_4000_8000_000000000000", i);
    }

    private static String extremo(String id, String asociacion, String clase, String bajo, String alto) {
        return "<ownedEnd xmi:type=\"uml:Property\" xmi:id=\"" + id + "\" visibility=\"public\" association=\""
                + asociacion + "\" aggregation=\"none\"><lowerValue xmi:type=\"uml:LiteralInteger\" xmi:id=\"" + id
                + "_lo\" value=\"" + bajo + "\"/><upperValue xmi:type=\"uml:LiteralUnlimitedNatural\" xmi:id=\"" + id
                + "_hi\" value=\"" + alto + "\"/><type xmi:idref=\"" + clase + "\"/></ownedEnd>";
    }

    /** Milisegundos de la mejor de tres lecturas (la primera calienta el JIT). */
    private static long medir(byte[] xmi) {
        long mejor = Long.MAX_VALUE;
        for (int i = 0; i < 3; i++) {
            long t0 = System.nanoTime();
            new LectorXmi().leer(xmi);
            mejor = Math.min(mejor, (System.nanoTime() - t0) / 1_000_000);
        }
        return mejor;
    }

    @Test
    void quinientas_clases_de_ea_se_leen_completas_y_rapido() {
        byte[] xmi = exportDeEa(500);
        ModeloLeido m = new LectorXmi().leer(xmi);

        assertThat(m.clases()).hasSize(500);
        assertThat(m.clases()).allSatisfy(c -> {
            assertThat(c.atributos()).hasSize(8);
            assertThat(c.metodos()).hasSize(2);
        });
        assertThat(m.relaciones()).filteredOn(r -> r.tipo() == RelationKind.ASSOCIATION).hasSize(499);
        assertThat(m.relaciones()).filteredOn(r -> r.tipo() == RelationKind.GENERALIZATION).hasSize(49);
        assertThat(m.posiciones()).hasSize(500);

        long ms = medir(xmi);
        System.out.printf("T-05 rendimiento: 500 clases, %.1f MB, %d ms%n", xmi.length / 1048576.0, ms);
        assertThat(ms).as("leer 500 clases de EA").isLessThan(5_000);
    }

    @Test
    void el_tiempo_crece_lineal_y_no_cuadratico() {
        byte[] chico = exportDeEa(200);
        byte[] grande = exportDeEa(1000);
        medir(chico);   // calentar
        long tChico = Math.max(1, medir(chico));
        long tGrande = medir(grande);
        System.out.printf("T-05 rendimiento: 200 clases %d ms (%.1f MB) · 1000 clases %d ms (%.1f MB)%n",
                tChico, chico.length / 1048576.0, tGrande, grande.length / 1048576.0);
        // 5 veces mas clases: lineal da ~5x; cuadratico, ~25x. Se tolera hasta 12x por el ruido de la medicion.
        assertThat(tGrande).as("1000 clases contra 200").isLessThan(Math.max(12 * tChico, 1_500));
    }
}
