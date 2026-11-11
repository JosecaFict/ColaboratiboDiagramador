package com.uagrm.colabmodel.ai.imagenes;

import com.uagrm.colabmodel.vision.ImagenDeEntrada.ImagenInvalida;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Pruebas 4, 9, 10, 11 y 12 del contrato (docs/DISENO-CHAT-IMAGENES.md, 11). Solo el JDK, sin base. */
class ReencodadorJdkTest {

    private static final String SECRETO = "SECRETO-GPS-4711";
    private final ReencodadorJdk reencodador = new ReencodadorJdk(40_000_000L);

    private static BufferedImage leer(byte[] b) throws IOException {
        return ImageIO.read(new ByteArrayInputStream(b));
    }

    private static boolean contiene(byte[] h, String aguja) {
        byte[] a = aguja.getBytes(StandardCharsets.ISO_8859_1);
        outer:
        for (int i = 0; i + a.length <= h.length; i++) {
            for (int j = 0; j < a.length; j++) if (h[i + j] != a[j]) continue outer;
            return true;
        }
        return false;
    }

    private static byte[] codificar(BufferedImage img, String formato) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        assertThat(ImageIO.write(img, formato, bos)).isTrue();
        return bos.toByteArray();
    }

    /** Una foto 'acostada' de 120x80 con la mitad izquierda roja y la derecha azul. */
    private static BufferedImage acostada() {
        BufferedImage img = new BufferedImage(120, 80, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 80; y++) for (int x = 0; x < 120; x++) img.setRGB(x, y, x < 60 ? 0xFF0000 : 0x0000FF);
        return img;
    }

    private static void segmento(ByteArrayOutputStream out, int marcador, byte[] datos) {
        out.write(0xFF);
        out.write(marcador);
        int largo = datos.length + 2;
        out.write(largo >> 8);
        out.write(largo & 0xFF);
        out.writeBytes(datos);
    }

    /** Le pone al JPEG un APP1 Exif (marca con el secreto + orientacion), un XMP y un comentario, como un telefono. */
    private static byte[] conExif(byte[] jpeg, int orientacion) {
        byte[] make = ("Marca-" + SECRETO + "\0").getBytes(StandardCharsets.ISO_8859_1);
        int offMake = 8 + 2 + 2 * 12 + 4;
        ByteBuffer t = ByteBuffer.allocate(offMake + make.length).order(ByteOrder.LITTLE_ENDIAN);
        t.put((byte) 'I').put((byte) 'I').putShort((short) 42).putInt(8);
        t.putShort((short) 2);
        t.putShort((short) 0x010F).putShort((short) 2).putInt(make.length).putInt(offMake);
        t.putShort((short) 0x0112).putShort((short) 3).putInt(1).putShort((short) orientacion).putShort((short) 0);
        t.putInt(0);
        t.put(make);
        ByteArrayOutputStream app1 = new ByteArrayOutputStream();
        app1.writeBytes("Exif\0\0".getBytes(StandardCharsets.ISO_8859_1));
        app1.writeBytes(t.array());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0xFF);
        out.write(0xD8);
        segmento(out, 0xE1, app1.toByteArray());
        segmento(out, 0xE1, ("http://ns.adobe.com/xap/1.0/\0XMP-" + SECRETO).getBytes(StandardCharsets.ISO_8859_1));
        segmento(out, 0xFE, ("COM-" + SECRETO).getBytes(StandardCharsets.ISO_8859_1));
        out.write(jpeg, 2, jpeg.length - 2);
        return out.toByteArray();
    }

    @Test
    void una_foto_con_exif_y_orientacion_6_sale_sin_exif_y_con_el_ancho_y_el_alto_intercambiados() throws IOException {
        byte[] foto = conExif(codificar(acostada(), "jpg"), 6);
        assertThat(contiene(foto, "Exif")).isTrue();
        assertThat(contiene(foto, SECRETO)).isTrue();

        var r = reencodador.reencodar(foto, "image/jpeg");

        assertThat(contiene(r.datos(), "Exif")).isFalse();
        assertThat(contiene(r.datos(), SECRETO)).isFalse();
        assertThat(r.tipo()).isEqualTo("image/jpeg");
        assertThat(r.ancho()).isEqualTo(80);
        assertThat(r.alto()).isEqualTo(120);
        BufferedImage salida = leer(r.datos());
        assertThat(salida.getWidth()).isEqualTo(80);
        assertThat(salida.getHeight()).isEqualTo(120);
        // Orientacion 6 = girar 90 grados a la derecha: la mitad izquierda (roja) queda ARRIBA.
        assertThat(new Color(salida.getRGB(40, 20)).getRed()).isGreaterThan(200);
        assertThat(new Color(salida.getRGB(40, 100)).getBlue()).isGreaterThan(200);
    }

    @Test
    void sin_orientacion_las_medidas_se_conservan() throws IOException {
        var r = reencodador.reencodar(codificar(acostada(), "jpg"), "image/jpeg");
        assertThat(r.ancho()).isEqualTo(120);
        assertThat(r.alto()).isEqualTo(80);
    }

    @Test
    void un_png_con_transparencia_queda_sobre_blanco_y_sigue_siendo_png() throws IOException {
        BufferedImage argb = new BufferedImage(64, 48, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 48; y++) for (int x = 0; x < 64; x++) argb.setRGB(x, y, x < 32 ? 0x00000000 : 0xFF000000);

        var r = reencodador.reencodar(codificar(argb, "png"), "image/png");

        assertThat(r.tipo()).isEqualTo("image/png");
        BufferedImage salida = leer(r.datos());
        assertThat(salida.getRGB(5, 5) & 0xFFFFFF).as("el transparente es blanco, no negro").isEqualTo(0xFFFFFF);
        assertThat(salida.getRGB(50, 5) & 0xFFFFFF).as("lo opaco no cambia").isEqualTo(0x000000);
        assertThat(r.miniatura()).isNotEmpty();
    }

    @Test
    void un_jpeg_cmyk_da_400_con_el_mensaje_de_cmyk() {
        byte[] cmyk = jpegDeCuatroComponentes();
        assertThatThrownBy(() -> reencodador.reencodar(cmyk, "image/jpeg"))
                .isInstanceOf(ImagenInvalida.class)
                .hasMessageContaining("CMYK")
                .hasMessageContaining("RGB");
    }

    /** Un JPEG cuyo SOF declara 4 componentes: basta el encabezado, el rechazo ocurre antes de decodificar. */
    private static byte[] jpegDeCuatroComponentes() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0xFF);
        out.write(0xD8);
        byte[] sof = {8, 0, 32, 0, 48, 4, 1, 0x11, 0, 2, 0x11, 0, 3, 0x11, 0, 4, 0x11, 0};
        segmento(out, 0xC0, sof);
        out.write(0xFF);
        out.write(0xD9);
        return out.toByteArray();
    }

    @Test
    void la_miniatura_mide_320_de_lado_mayor_conserva_la_proporcion_y_es_jpeg() throws IOException {
        BufferedImage grande = new BufferedImage(1600, 1200, BufferedImage.TYPE_INT_RGB);
        var r = reencodador.reencodar(codificar(grande, "png"), "image/png");

        BufferedImage mini = leer(r.miniatura());
        assertThat(mini.getWidth()).isEqualTo(320);
        assertThat(mini.getHeight()).isEqualTo(240);
        assertThat(r.miniatura()[0] & 0xFF).isEqualTo(0xFF);
        assertThat(r.miniatura()[1] & 0xFF).isEqualTo(0xD8);
    }

    @Test
    void la_miniatura_de_una_imagen_vertical_tambien_mide_320_de_lado_mayor() throws IOException {
        var r = reencodador.reencodar(codificar(new BufferedImage(100, 700, BufferedImage.TYPE_INT_RGB), "png"),
                "image/png");
        BufferedImage mini = leer(r.miniatura());
        assertThat(mini.getHeight()).isEqualTo(320);
        assertThat(mini.getWidth()).isEqualTo(46);
    }

    @Test
    void una_imagen_chica_no_se_amplia() throws IOException {
        var r = reencodador.reencodar(codificar(new BufferedImage(100, 60, BufferedImage.TYPE_INT_RGB), "png"),
                "image/png");
        BufferedImage mini = leer(r.miniatura());
        assertThat(mini.getWidth()).isEqualTo(100);
        assertThat(mini.getHeight()).isEqualTo(60);
    }

    @Test
    void el_reencodado_usa_la_imagen_completa_y_no_la_copia_de_1600() throws IOException {
        var r = reencodador.reencodar(codificar(new BufferedImage(2400, 1800, BufferedImage.TYPE_INT_RGB), "png"),
                "image/png");
        assertThat(r.ancho()).isEqualTo(2400);
        assertThat(r.alto()).isEqualTo(1800);
    }

    @Test
    void las_ocho_orientaciones_dan_las_medidas_esperadas() throws IOException {
        byte[] base = codificar(acostada(), "jpg");
        for (int o = 1; o <= 8; o++) {
            var r = reencodador.reencodar(conExif(base, o), "image/jpeg");
            boolean gira = o >= 5;
            assertThat(r.ancho()).as("ancho con orientacion " + o).isEqualTo(gira ? 80 : 120);
            assertThat(r.alto()).as("alto con orientacion " + o).isEqualTo(gira ? 120 : 80);
        }
    }

    @Test
    void un_exif_cortado_o_con_valor_fuera_de_rango_no_revienta_y_no_gira() throws IOException {
        byte[] base = codificar(acostada(), "jpg");
        assertThat(ReencodadorJdk.orientacionExif(conExif(base, 99))).isEqualTo(1);
        byte[] cortado = java.util.Arrays.copyOf(conExif(base, 6), 40);
        assertThat(ReencodadorJdk.orientacionExif(cortado)).isEqualTo(1);
        assertThat(ReencodadorJdk.orientacionExif(new byte[]{1, 2, 3})).isEqualTo(1);
    }
}
