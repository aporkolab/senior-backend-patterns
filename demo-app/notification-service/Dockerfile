FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY target/*.jar app.jar
RUN apk add --no-cache curl
EXPOSE 8083
ENTRYPOINT ["java", "-jar", "app.jar"]
