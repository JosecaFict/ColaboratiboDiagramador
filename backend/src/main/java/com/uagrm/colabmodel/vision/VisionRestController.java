package com.uagrm.colabmodel.vision;

import com.uagrm.colabmodel.project.AccesoAProyecto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.security.Principal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Leer una imagen y PROPONER un diagrama. No escribe nada.
 *
 * Es plano de CONTROL (REST), como el resto de las lecturas: el plano de datos
 * sigue yendo por WebSocket y no se mezclan.
 *
 * ESTE ENDPOINT NO TOCA EL MODELO. No toma lock, no entra al log de
 * operaciones y no agrega ningun comando al catalogo cerrado (ADR-014).
 * Devuelve candidatos; lo que el usuario confirme en el panel se emitira
 * despues como Commands normales con origin IMAGE, por el mismo bus que el
 * mouse (ADR-002). No hay un segundo camino de escritura.
 *
 * POR QUE PIDE PERMISO DE ESCRITURA aunque no escriba. Un VIEWER no puede
 * aterrizar la propuesta, asi que para el es trabajo de CPU que no va a poder
 * usar --y este es el unico endpoint del sistema que decodifica un binario
 * arbitrario de afuera--. Es lo contrario del criterio del zip de codegen, que
 * si va con permiso de lectura: ahi el VIEWER obtiene algo que ya podia ver en
 * la Vista Logica; aca obtendria el primer paso de una edicion que tiene
 * prohibida.
 */
@RestController
@RequestMapping("/api/v1/diagrams/{id}/vision")
public class VisionRestController {

    private static final Logger log = LoggerFactory.getLogger(VisionRestController.class);

    private final DetectorDeDiagrama detector;
    private final List<EnriquecedorDeDeteccion> enriquecedores;
    private final AccesoAProyecto acceso;
    private final ImagenDeEntrada.Limites limites;

    /**
     * @param enriquecedores los niveles 2 disponibles, en el orden en que Spring
     *                       los ordene. Se aplican EN CADENA sobre la misma
     *                       propuesta: uno puede clasificar los simbolos sin
     *                       modelo y otro leer los textos con uno. Que sea una
     *                       lista y no un solo bean es lo que permite agregar o
     *                       sacar capas sin tocar este controlador
     */
    public VisionRestController(DetectorDeDiagrama detector,
                                List<EnriquecedorDeDeteccion> enriquecedores,
                                AccesoAProyecto acceso,
                                @Value("${colabmodel.vision.max-bytes:8388608}") long maxBytes,
                                @Value("${colabmodel.vision.max-pixeles:40000000}") long maxPixeles,
                                @Value("${colabmodel.vision.max-lado:1600}") int maxLado) {
        this.detector = detector;
        this.enriquecedores = enriquecedores;
        this.acceso = acceso;
        this.limites = new ImagenDeEntrada.Limites(maxBytes, maxPixeles, maxLado);
    }

    /**
     * Imagen entra, propuesta sale. La imagen no se guarda en ningun lado.
     *
     * @param imagen PNG o JPEG. Una captura de proyector o un screenshot dan
     *               mucho mejor resultado que una foto: no tienen perspectiva
     *               ni brillo, y el texto esta renderizado
     */
    @PostMapping(value = "/deteccion", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public DeteccionPropuesta detectar(@PathVariable UUID id,
                                       @RequestPart("imagen") MultipartFile imagen,
                                       Principal principal) {
        exigirEscritura(id, principal);

        byte[] bytes;
        try {
            bytes = imagen.getBytes();
        } catch (IOException e) {
            throw new ImagenDeEntrada.ImagenInvalida("No se pudo leer el archivo subido");
        }

        ImagenDeEntrada entrada = ImagenDeEntrada.desde(bytes, imagen.getContentType(), limites);
        DeteccionPropuesta base = detector.detectar(entrada);
        return enriquecer(base, entrada);
    }

    /**
     * Nivel 2 si esta; si no, o si falla, el nivel 1 tal cual (ADR-012).
     *
     * La degradacion esta ESCRITA ACA, no prometida en un documento: un modelo
     * local se cae, se queda sin memoria o lo apagan, y la unica diferencia
     * aceptable es que la propuesta salga sin textos. Que una deteccion entera
     * se pierda porque el enriquecedor tiro una excepcion seria convertir una
     * capa opcional en un punto unico de falla.
     */
    private DeteccionPropuesta enriquecer(DeteccionPropuesta base, ImagenDeEntrada entrada) {
        DeteccionPropuesta actual = base;
        for (EnriquecedorDeDeteccion nivel2 : enriquecedores) {
            try {
                if (!nivel2.disponible()) continue;
                DeteccionPropuesta siguiente = nivel2.enriquecer(actual, entrada);
                // Devolver null es un incumplimiento del contrato, no un
                // resultado: se ignora esa capa y se sigue con la anterior.
                if (siguiente != null) actual = siguiente;
            } catch (RuntimeException e) {
                // Una capa que se cae no puede llevarse la deteccion entera:
                // se pierde lo que esa capa aportaba y nada mas.
                log.warn("El enriquecedor {} fallo; se sigue con lo que habia", nivel2.nombre(), e);
            }
        }
        return actual;
    }

    /** Solo OWNER y EDITOR. Misma fuente de verdad que el bus y el snapshot. */
    private void exigirEscritura(UUID diagramId, Principal principal) {
        UUID userId = UUID.fromString(principal.getName());
        AccesoAProyecto.Rol rol = acceso.rolSobreDiagrama(diagramId, userId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.FORBIDDEN, "No sos miembro de este proyecto"));
        if (!acceso.puedeEditar(rol)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Un VIEWER no puede cargar imagenes para editar el diagrama");
        }
    }

    /** Entrada mala es 400, nunca 500: el que sube el archivo puede corregirlo. */
    @ExceptionHandler(ImagenDeEntrada.ImagenInvalida.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> imagenInvalida(ImagenDeEntrada.ImagenInvalida e) {
        return Map.of("error", "IMAGEN_INVALIDA", "mensaje", e.getMessage());
    }
}
