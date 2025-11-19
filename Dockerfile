FROM eclipse-temurin:21-jre
COPY ./build/libs/mne-estate-parser-*.jar application.jar
ENTRYPOINT ["java", "-jar", "application.jar"]
