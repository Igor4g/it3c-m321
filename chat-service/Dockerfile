# Stufe 1: bauen
# Der Build-Kontext ist das Projekt-Wurzelverzeichnis, weil das Modul
# das Eltern-POM braucht.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
COPY chat-service/pom.xml chat-service/pom.xml
# Maven liest auch das POM des Geschwistermoduls aus der Eltern-Modulliste.
COPY batch-writer/pom.xml batch-writer/pom.xml
COPY chat-service/src chat-service/src
# Tests werden hier übersprungen: Testcontainers bräuchte einen Docker-Daemon
# INNERHALB des Builds. Getestet wird vorher mit "mvn test".
RUN mvn -q -pl chat-service -am package -DskipTests

# Stufe 2: laufen
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /build/chat-service/target/chat-service-0.1.0-SNAPSHOT.jar app.jar
# EXPOSE dokumentiert den Port nur. Veröffentlicht wird er NICHT —
# siehe docker-compose.yml, dort steht bewusst kein ports:-Eintrag.
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
