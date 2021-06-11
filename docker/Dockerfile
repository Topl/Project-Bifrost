FROM ghcr.io/graalvm/graalvm-ce:java11-21.1.0

RUN mkdir -p /usr/src/bifrost

COPY ./bifrost-1.6.3.jar /usr/src/bifrost

WORKDIR /usr/src/bifrost

CMD ["java", "-jar", "bifrost-1.6.3.jar", "--seed", "test", "-f", "--apiKeyHash", "6ju8SfmsrZbjCRJ8FXH8Bgygb7L3sNo3jCfcsYeDSrC2"]