package io.janstenpickle.trace4cats.opentelemetry.otlp

import java.time.Instant

import cats.effect.IO
import io.janstenpickle.trace4cats.`export`.SemanticTags
import io.janstenpickle.trace4cats.model.{AttributeValue, Batch, CompletedSpan, TraceProcess}
import io.janstenpickle.trace4cats.test.jaeger.BaseJaegerSpec

import scala.concurrent.duration._

class OpenTelemetryOtlpGrpcSpanCompleterSpec extends BaseJaegerSpec {
  it should "Send a span to jaeger" in forAll { (span: CompletedSpan, serviceName: String) =>
    val process = TraceProcess(serviceName)

    val updatedSpan = span.copy(start = Instant.now(), end = Instant.now())
    val batch = Batch(process, List(updatedSpan))

    testCompleter(
      OpenTelemetryOtlpGrpcSpanCompleter[IO](blocker, process, "localhost", 55680, batchTimeout = 50.millis),
      updatedSpan,
      process,
      batchToJaegerResponse(batch, SemanticTags.kindTags.andThen(_.filterNot {
        case (k, AttributeValue.StringValue(v)) => k == "span.kind" && v == "internal"
        case _ => false
      }), SemanticTags.statusTags("", requireMessage = false))
    )
  }
}
