FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B package -DskipTests

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
EXPOSE 8080
# The workbook ingest opens each HR Ops file as a full in-memory DOM (Apache POI, non-streaming) —
# the attendance register alone is ~900k rows. Explicit heap rather than the JVM's container-aware
# default, which under a constrained VM can compute to a few hundred MB and OOM mid-parse.
ENTRYPOINT ["java", "-Xms512m", "-Xmx3g", "-jar", "app.jar"]
