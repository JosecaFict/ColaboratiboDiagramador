# Imagen de produccion del backend (P1). La usa deploy/docker-compose.prod.yml.
# Toda la configuracion de produccion entra por variables de entorno: este
# archivo no lleva claves ni direcciones.

# --- 1. Compilar -------------------------------------------------------------
FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /app
# Primero solo el pom: si no cambia, Docker reutiliza las dependencias bajadas.
COPY pom.xml .
RUN mvn -q -B dependency:go-offline || true
COPY src ./src
# Las pruebas se corren en desarrollo; aca solo se empaqueta.
RUN mvn -q -B -DskipTests package

# --- 2. Ejecutar -------------------------------------------------------------
FROM eclipse-temurin:21-jre
# Fuentes para ImageIO/AWT (miniaturas y lectura de imagenes) en modo headless.
RUN apt-get update \
 && apt-get install -y --no-install-recommends fontconfig fonts-dejavu-core \
 && rm -rf /var/lib/apt/lists/* \
 && useradd --system --create-home colabmodel
WORKDIR /app
COPY --from=build /app/target/colabmodel-*.jar app.jar
USER colabmodel
# La JVM toma como maximo el 60 % de la memoria del contenedor.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=60 -Djava.awt.headless=true"
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
