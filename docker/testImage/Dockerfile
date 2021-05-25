FROM ghcr.io/graalvm/graalvm-ce:java11-21.1.0

ENV SCALA_VERSION 2.13.5
ENV SBT_VERSION 1.5.0

RUN microdnf update -y \
 && microdnf install -y wget git \
 && curl https://bintray.com/sbt/rpm/rpm | tee /etc/yum.repos.d/bintray-sbt-rpm.repo \
 && microdnf install -y sbt
