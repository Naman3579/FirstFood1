
# --- Build stage ---
FROM maven:3.9-eclipse-temurin-25 AS build

WORKDIR /app

COPY pom.xml ./

# Maven dependency download with retry and persistent cache
RUN --mount=type=cache,target=/root/.m2 \
    for i in 1 2 3 4 5; do \
      if mvn -B -ntp dependency:go-offline; then \
        break; \
      fi; \
      if [ "$i" -eq 5 ]; then exit 1; fi; \
      echo "Retrying Maven download in 30 seconds..."; \
      sleep 30; \
    done

COPY src ./src

RUN --mount=type=cache,target=/root/.m2 \
    mvn -q -B -ntp clean package -DskipTests

# --- Runtime stage ---
FROM eclipse-temurin:25-jre-alpine

WORKDIR /app

RUN addgroup -S firstfood && adduser -S firstfood -G firstfood

COPY --from=build /app/target/*.jar app.jar

RUN chown firstfood:firstfood app.jar

USER firstfood

EXPOSE 8080

ENV SPRING_PROFILES_ACTIVE=production

HEALTHCHECK --interval=30s --timeout=3s --start-period=30s \
  CMD wget -qO- http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["java", "-jar", "app.jar"]
