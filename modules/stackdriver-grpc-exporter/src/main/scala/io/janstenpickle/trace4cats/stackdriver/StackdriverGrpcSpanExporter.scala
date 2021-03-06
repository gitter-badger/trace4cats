package io.janstenpickle.trace4cats.stackdriver

import java.time.Instant

import cats.effect.{Blocker, Concurrent, ContextShift, Resource, Sync, Timer}
import cats.syntax.functor._
import cats.syntax.show._
import com.google.api.gax.core.FixedCredentialsProvider
import com.google.auth.Credentials
import com.google.auth.oauth2.GoogleCredentials
import com.google.cloud.trace.v2.{TraceServiceClient, TraceServiceSettings}
import com.google.devtools.cloudtrace.v2.Span.Attributes
import com.google.devtools.cloudtrace.v2.{TruncatableString => GTruncatableString, AttributeValue => GAttributeValue, _}
import com.google.protobuf.{BoolValue, Timestamp}
import com.google.rpc.Status
import io.janstenpickle.trace4cats.kernel.SpanExporter
import io.janstenpickle.trace4cats.model._
import io.janstenpickle.trace4cats.stackdriver.common.StackdriverConstants._
import io.janstenpickle.trace4cats.stackdriver.common.TruncatableString

import scala.concurrent.duration._
import scala.jdk.CollectionConverters._

object StackdriverGrpcSpanExporter {
  def apply[F[_]: Concurrent: ContextShift: Timer](
    blocker: Blocker,
    projectId: String,
    credentials: Option[Credentials] = None,
    requestTimeout: FiniteDuration = 5.seconds
  ): Resource[F, SpanExporter[F]] = {
    val projectName = ProjectName.of(projectId)

    val traceClient: F[TraceServiceClient] = Sync[F].delay {
      val creds = credentials.getOrElse(GoogleCredentials.getApplicationDefault())

      val clientBuilder = TraceServiceSettings.newBuilder
        .setCredentialsProvider(FixedCredentialsProvider.create(creds))

      clientBuilder
        .batchWriteSpansSettings()
        .setSimpleTimeoutNoRetries(org.threeten.bp.Duration.ofMillis(requestTimeout.toMillis))

      TraceServiceClient.create(clientBuilder.build())
    }

    def toTruncatableStringProto(string: String) = {
      val truncatableString = TruncatableString(string)
      GTruncatableString.newBuilder
        .setValue(truncatableString.value)
        .setTruncatedByteCount(truncatableString.truncatedByteCount)
        .build
    }

    def toTimestampProto(timestamp: Instant): Timestamp =
      Timestamp.newBuilder.setSeconds(timestamp.getEpochSecond).setNanos(timestamp.getNano).build

    def toDisplayName(spanName: String, spanKind: SpanKind) = spanKind match {
      case SpanKind.Server if !spanName.startsWith(ServerPrefix) => ServerPrefix + spanName
      case SpanKind.Client if !spanName.startsWith(ClientPrefix) => ClientPrefix + spanName
      case SpanKind.Consumer if !spanName.startsWith(ServerPrefix) => ServerPrefix + spanName
      case SpanKind.Producer if !spanName.startsWith(ClientPrefix) => ClientPrefix + spanName
      case _ => spanName
    }

    def toAttributesProto(process: TraceProcess, attributes: Map[String, AttributeValue]): Attributes =
      (process.attributes.updated(ServiceNameAttributeKey, process.serviceName) ++ attributes).toList
        .foldLeft(Attributes.newBuilder()) {
          case (acc, (k, v)) =>
            acc.putAttributeMap(
              k,
              (v match {
                case AttributeValue.StringValue(value) =>
                  GAttributeValue.newBuilder().setStringValue(toTruncatableStringProto(value))
                case AttributeValue.BooleanValue(value) => GAttributeValue.newBuilder().setBoolValue(value)
                case AttributeValue.DoubleValue(value) => GAttributeValue.newBuilder().setIntValue(value.toLong)
              }).build()
            )

        }
        .build()

    def toStatusProto(status: SpanStatus) =
      Status
        .newBuilder()
        .setCode(status.canonicalCode)
        .build()

    def convert(process: TraceProcess, completedSpan: CompletedSpan): Span = {
      val spanIdHex = completedSpan.context.spanId.show

      val spanName =
        SpanName.newBuilder.setProject(projectId).setTrace(completedSpan.context.traceId.show).setSpan(spanIdHex).build

      val spanBuilder =
        Span
          .newBuilder()
          .setName(spanName.toString)
          .setSpanId(spanIdHex)
          .setDisplayName(toTruncatableStringProto(toDisplayName(completedSpan.name, completedSpan.kind)))
          .setStartTime(toTimestampProto(completedSpan.start))
          .setEndTime(toTimestampProto(completedSpan.end))
          .setAttributes(toAttributesProto(process, completedSpan.attributes))
          .setStatus(toStatusProto(completedSpan.status))

      val builder = completedSpan.context.parent.fold(spanBuilder) { parent =>
        spanBuilder.setParentSpanId(parent.spanId.show).setSameProcessAsParentSpan(BoolValue.of(!parent.isRemote))
      }

      builder.build()
    }

    def write(client: TraceServiceClient, process: TraceProcess, spans: List[CompletedSpan]) =
      blocker.delay(client.batchWriteSpans(projectName, spans.map(convert(process, _)).asJava))

    Resource.make(traceClient)(client => Sync[F].delay(client.shutdown())).map { client =>
      new SpanExporter[F] {
        override def exportBatch(batch: Batch): F[Unit] = write(client, batch.process, batch.spans).void
      }
    }
  }
}
