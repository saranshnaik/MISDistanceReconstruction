# Stage 1: build the jar with the Maven wrapper (no local Maven needed)
FROM eclipse-temurin:21-jdk AS build
WORKDIR /build
COPY mvnw pom.xml ./
COPY .mvn .mvn
RUN ./mvnw -B -q dependency:go-offline
COPY src src
RUN ./mvnw -B -q package -DskipTests

# Stage 2: small runtime image
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /build/target/distance-engine-*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
