# Этап 1: Сборка приложения с помощью Maven
FROM maven:3.9.9-eclipse-temurin-21 AS builder
WORKDIR /app
COPY pom.xml .
COPY src ./src
# Собираем проект, пропуская тесты для скорости
RUN mvn clean package -DskipTests

# Этап 2: Создание легкого финального образа только с JRE
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
# Копируем собранный jar-файл из этапа builder
COPY --from=builder /app/target/*.jar app.jar
# Запускаем приложение
ENTRYPOINT ["java", "-jar", "app.jar"]