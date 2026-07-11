FROM public.ecr.aws/amazoncorretto/amazoncorretto:21

COPY target/*.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app.jar"]
