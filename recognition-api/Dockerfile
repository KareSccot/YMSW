FROM maven:3.9.9-eclipse-temurin-17 AS builder

WORKDIR /build

COPY apps/api/pom.xml ./pom.xml
COPY apps/api/src ./src

RUN mvn -q -DskipTests package

FROM eclipse-temurin:17-jre-alpine

WORKDIR /app

ENV TZ=Asia/Shanghai

RUN apk add --no-cache chromium fontconfig font-noto-cjk \
    && addgroup -S spring \
    && adduser -S spring -G spring \
    && mkdir -p /app/uploads/template-images \
    && chown -R spring:spring /app/uploads

COPY --from=builder /build/target/employee-care-1.0.0-SNAPSHOT.jar /app/app.jar

USER spring

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
