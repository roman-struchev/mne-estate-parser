FROM openjdk:21-slim
COPY ./build/libs/mne-estate-parser-*.jar application.jar
ENTRYPOINT ["java", "-jar", "application.jar"]