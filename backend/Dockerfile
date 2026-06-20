FROM maven:3.9.9-eclipse-temurin-17 AS build
WORKDIR /workspace
COPY pom.xml .
RUN mvn -q -DskipTests dependency:go-offline
COPY src ./src
RUN mvn -q -DskipTests package

FROM mcr.microsoft.com/dotnet/sdk:8.0 AS roslyn-build
WORKDIR /workspace
COPY roslyn-analyzer ./roslyn-analyzer
RUN dotnet publish roslyn-analyzer/LearnBot.RoslynAnalyzer.csproj -c Release -o /out

FROM mcr.microsoft.com/dotnet/runtime:8.0 AS dotnet-runtime

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /workspace/target/learnbot-backend-0.1.0.jar app.jar
COPY --from=dotnet-runtime /usr/share/dotnet /usr/share/dotnet
COPY --from=dotnet-runtime /usr/lib/x86_64-linux-gnu/libicudata.so.72* /usr/lib/x86_64-linux-gnu/
COPY --from=dotnet-runtime /usr/lib/x86_64-linux-gnu/libicui18n.so.72* /usr/lib/x86_64-linux-gnu/
COPY --from=dotnet-runtime /usr/lib/x86_64-linux-gnu/libicuuc.so.72* /usr/lib/x86_64-linux-gnu/
COPY --from=roslyn-build /out /app/roslyn
RUN ln -s /usr/share/dotnet/dotnet /usr/bin/dotnet
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
