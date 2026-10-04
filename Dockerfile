# Stage 1: build the jar with the project's Maven wrapper.
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app

# Dependencies first, so they stay cached until pom.xml changes.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -B -q dependency:go-offline

COPY src/ src/
RUN ./mvnw -B -q package -DskipTests

# Stage 2: run it on a JRE only, as a non-root user.
FROM eclipse-temurin:21-jre
WORKDIR /app
RUN groupadd --system app && useradd --system --gid app app
COPY --from=build /app/target/*.jar app.jar
USER app
EXPOSE 8080
# headless: brand logos are resized with Java's image tools, which must never look for a screen.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-Djava.awt.headless=true", "-jar", "app.jar"]
