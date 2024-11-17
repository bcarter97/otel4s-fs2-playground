val scala3Version = "3.5.2"

lazy val root = project
  .in(file("."))
  .settings(
    name := "otel4s-fs2-playground",
    version := "0.1.0-SNAPSHOT",
    run / fork := true,

    scalaVersion := scala3Version,


    libraryDependencies ++= Seq(
      "org.typelevel" %% "cats-effect" % "3.5.5",
      "co.fs2" %% "fs2-core" % "3.11.0",
      "org.typelevel" %% "otel4s-oteljava" % "0.11.1",
      "io.opentelemetry" % "opentelemetry-exporter-otlp" % "1.44.1" % Runtime,
      "io.opentelemetry" % "opentelemetry-sdk-extension-autoconfigure" % "1.44.1" % Runtime,
    ),

    javaAgents += "io.opentelemetry.javaagent" % "opentelemetry-javaagent" % "2.10.0" % Runtime,

    javaOptions ++= Seq(
      "-Dotel.java.global-autoconfigure.enabled=true",
      "-Dotel.exporter.otlp.endpoint=http://localhost:4317",
      "-Dotel.exporter.otlp.traces.protocol=grpc",
      "-Dotel.exporter.prometheus.port=9401",
      "-Dotel.logs.exporter=none",
      "-Dotel.metrics.exporter=prometheus",
      "-Dotel.service.name=otel4s-fs2",
    )

  ).enablePlugins(JavaAgent)
