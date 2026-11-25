# Builds and runs the Skill Exchange server in a container, for a web hosting service such as Render.
# The server answers two kinds of client on one port: browsers get the web site (pages rendered by
# Java, no JavaScript), and the JavaFX desktop application connects with --server=https://...
# Nothing in this file is needed to run the project on your own machine.

# Stage 1: build skill-exchange.jar with Maven and the full JDK.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN mvn -q -B -DskipTests package

# Stage 2: run on the smaller JRE image, with only the JAR.
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/target/skill-exchange.jar .

# The app works in one local time zone: slot times are stored and shown as plain local times.
# Set this to the time zone of the people using it, or countdowns will be off by the difference.
ENV TZ=Asia/Kolkata

# The hosting service chooses the port and passes it in PORT; 10000 is Render's default.
# A web host carries only HTTP, so the socket port is switched off with --http-only.
ENV PORT=10000
EXPOSE 10000

CMD ["java", "-jar", "skill-exchange.jar", "server", "--http-only", "--data", "data"]
