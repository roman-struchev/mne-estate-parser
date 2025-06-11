FROM openjdk:21-slim
COPY ./build/libs/mne-estate-parser-*.jar application.jar
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar application.jar"]
