FROM eclipse-temurin:21-jdk-alpine as builder
WORKDIR /app
COPY pom.xml .
COPY src ./src
# Would need Maven wrapper or mvn installed
# For now, assume pre-built JAR
COPY target/*.jar app.jar

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=builder /app/app.jar app.jar

# Add curl for healthchecks
RUN apk add --no-cache curl

EXPOSE 8081
ENTRYPOINT ["java", "-jar", "app.jar"]
