FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY . .
RUN mvn -B -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN groupadd --system agent && useradd --system --gid agent agent && mkdir /app/var && chown agent:agent /app/var
COPY --from=build /workspace/ecom-agent-bootstrap/target/ecom-agent-bootstrap-0.1.0-SNAPSHOT.jar /app/agent.jar
USER agent
ENV SERVER_ADDRESS=0.0.0.0
EXPOSE 8080
ENTRYPOINT ["java","-jar","/app/agent.jar"]

