import assert from "node:assert/strict";
import test from "node:test";

import type { SQSEvent, SQSRecord } from "aws-lambda";

import { handleSqsEvent, type MessageProcessor } from "../src/handler";

test("returns only failed SQS records for partial batch retry", async () => {
  const processor: MessageProcessor = {
    async processMessage(body: string) {
      if (body === "retry") {
        throw new Error("transient storage failure");
      }
    },
  };
  const previousConsoleError = console.error;
  const previousEnvironment = process.env.MEDIA_ENVIRONMENT;
  const logs: string[] = [];
  console.error = (message?: unknown) => logs.push(String(message));
  process.env.MEDIA_ENVIRONMENT = "dev";
  try {
    const response = await handleSqsEvent(
      sqsEvent([
        sqsRecord("message-1", "success"),
        sqsRecord("message-2", "retry"),
        sqsRecord("message-3", "success"),
      ]),
      processor,
    );

    assert.deepEqual(response, {
      batchItemFailures: [{ itemIdentifier: "message-2" }],
    });
    assert.equal(logs.length, 1);
    const log = JSON.parse(logs[0]!) as Record<string, unknown>;
    assert.equal(log.event, "image_transform_record_failed");
    assert.equal(log.errorName, "Error");
    assert.equal(log.messageId, "message-2");
    assert.equal(log.Environment, "dev");
    assert.equal(log.ImageTransformRecordFailures, 1);
    const metricEnvelope = log._aws as {
      Timestamp: number;
      CloudWatchMetrics: Array<Record<string, unknown>>;
    };
    assert.equal(typeof metricEnvelope.Timestamp, "number");
    assert.deepEqual(metricEnvelope.CloudWatchMetrics, [
      {
        Namespace: "HASHI/Media",
        Dimensions: [["Environment"]],
        Metrics: [{ Name: "ImageTransformRecordFailures", Unit: "Count" }],
      },
    ]);
    assert.equal("body" in log, false);
  } finally {
    console.error = previousConsoleError;
    if (previousEnvironment === undefined) {
      delete process.env.MEDIA_ENVIRONMENT;
    } else {
      process.env.MEDIA_ENVIRONMENT = previousEnvironment;
    }
  }
});

function sqsEvent(records: SQSRecord[]): SQSEvent {
  return { Records: records };
}

function sqsRecord(messageId: string, body: string): SQSRecord {
  return {
    messageId,
    receiptHandle: "receipt",
    body,
    attributes: {
      ApproximateReceiveCount: "1",
      SentTimestamp: "0",
      SenderId: "sender",
      ApproximateFirstReceiveTimestamp: "0",
    },
    messageAttributes: {},
    md5OfBody: "md5",
    eventSource: "aws:sqs",
    eventSourceARN: "arn:aws:sqs:ap-northeast-2:123:request",
    awsRegion: "ap-northeast-2",
  };
}
