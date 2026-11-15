FROM eclipse-temurin:17-jdk AS build
WORKDIR /src
COPY . .
RUN ./gradlew installDist --no-daemon

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /src/build/install/CricketScoringBackend /app
EXPOSE 8080
ENTRYPOINT ["/app/bin/CricketScoringBackend"]
