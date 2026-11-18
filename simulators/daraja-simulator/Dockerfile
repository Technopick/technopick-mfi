# TEST-ONLY Daraja simulator image - never deployed. Build first:
#   mvn -pl simulators/daraja-simulator -am package
#   mvn -pl simulators/daraja-simulator dependency:copy-dependencies -DincludeScope=runtime -DoutputDirectory=target/lib
FROM eclipse-temurin:25-jre
LABEL ke.co.technopick.test-only="true" \
      org.opencontainers.image.description="TEST-ONLY Safaricom Daraja simulator. Not for production."
RUN useradd --system --uid 10001 --home /app sim && mkdir /shared && chown 10001 /shared
WORKDIR /app
COPY target/lib/ lib/
COPY target/daraja-simulator-*.jar lib/
USER 10001
EXPOSE 18090
ENTRYPOINT ["java", "-cp", "/app/lib/*", "ke.co.technopick.mfi.simulators.daraja.DarajaSimulator"]
