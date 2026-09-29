# Stufe 1: das eigenständige Writer-Modul mit Java 21 bauen.
# Maven benötigt das Eltern-POM und die POMs beider eingetragenen Module.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
COPY chat-service/pom.xml chat-service/pom.xml
COPY batch-writer/pom.xml batch-writer/pom.xml
COPY batch-writer/src batch-writer/src
# Container-Tests laufen vorher mit mvn clean test, nicht innerhalb des Image-Builds.
RUN mvn -q -pl batch-writer -am package -DskipTests

# Stufe 2: zum Ausführen reicht die Java-Laufzeit ohne Maven.
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /build/batch-writer/target/batch-writer-0.1.0-SNAPSHOT.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]
