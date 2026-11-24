# Builds and runs the web app in a container, for hosting services such as Render.
# Nothing here is needed to run the project on your own machine.

# Stage 1: compile the Java sources with the full JDK.
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY src ./src
RUN javac -d out $(find src -name "*.java")

# Stage 2: run on the smaller JRE image, with only the compiled classes and the front end.
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/out ./out
COPY web ./web

# The app works in one local time zone: slot times are stored and shown as plain local times.
# Set this to the time zone of the people using it, or countdowns will be off by the difference.
ENV TZ=Asia/Kolkata

# The hosting service chooses the port and passes it in PORT; 10000 is Render's default.
# The server also needs its public name, in PUBLIC_HOST (Render supplies RENDER_EXTERNAL_HOSTNAME itself).
ENV PORT=10000
ENV LISTEN_ALL=true
EXPOSE 10000

CMD ["java", "-cp", "out", "skillexchange.web.WebServer"]
