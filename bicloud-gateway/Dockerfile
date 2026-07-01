# Alpine (musl) DEGIL — docker-java'nin unix socket katmani (junixsocket) musl'da
# yuklenemiyor ve /var/run/docker.sock baglantisi "Connection refused" ile dusuyor.
FROM eclipse-temurin:21-jre

WORKDIR /app

COPY target/bicloud-gateway-*.jar app.jar

EXPOSE 9000

# Container içinde unix socket kullanılır (named pipe değil)
ENV SPRING_APPLICATION_NAME=bicloud-gateway

ENTRYPOINT ["java", "-jar", "app.jar"]
