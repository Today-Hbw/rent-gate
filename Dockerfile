FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
COPY src src
RUN mvn -B -ntp verify

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN groupadd --system rentgate && useradd --system --gid rentgate rentgate && mkdir /app/data && chown rentgate:rentgate /app/data
COPY --from=build /build/target/rent-gate-0.3.0.jar app.jar
USER rentgate
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
