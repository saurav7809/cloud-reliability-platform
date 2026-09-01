# syntax=docker/dockerfile:1

# ---- build ----------------------------------------------------------------
# Dependencies resolve in their own layer, keyed on pom.xml alone, so editing
# source does not re-download the world on every build.
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app

COPY pom.xml ./
RUN mvn -B -q dependency:go-offline

COPY src ./src
RUN mvn -B -q -DskipTests package

# ---- runtime --------------------------------------------------------------
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app

# The control plane holds cluster credentials and can change running
# infrastructure. It has no reason to be root inside its own container.
RUN addgroup -S aegis && adduser -S aegis -G aegis
USER aegis

COPY --from=build /app/target/control-plane-*.jar /app/control-plane.jar

EXPOSE 8080

# MaxRAMPercentage rather than a fixed -Xmx: the JVM then respects whatever
# memory limit the container is actually given, instead of a number guessed here.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0"

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/control-plane.jar"]
