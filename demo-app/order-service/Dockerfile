FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY target/*.jar app.jar
RUN apk add --no-cache curl
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "app.jar"]
