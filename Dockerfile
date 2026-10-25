FROM maven:3.9.11-eclipse-temurin-25 AS build

WORKDIR /workspace
COPY . .
RUN mvn -B -DskipTests package dependency:copy-dependencies \
    -DincludeScope=runtime \
    -DoutputDirectory=target/dependency

FROM eclipse-temurin:25-jre

WORKDIR /app
ENV BIND=0.0.0.0
ENV PORT=8080
ENV PRODUCTION=true

COPY --from=build /workspace/target/palm-internship-portal-1.0.0.jar /app/app.jar
COPY --from=build /workspace/target/dependency/ /app/dependency/
COPY --from=build /workspace/static/ /app/static/

RUN mkdir -p /app/cvs /app/reports \
    && chown -R 10001:10001 /app/cvs /app/reports
USER 10001:10001

EXPOSE 8080
ENTRYPOINT ["java", "-cp", "/app/app.jar:/app/dependency/*", "Server"]
