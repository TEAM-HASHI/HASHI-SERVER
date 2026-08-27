import type { SQSBatchResponse, SQSEvent, SQSRecord } from "aws-lambda";

import { AwsImageObjectStorage, SqsTransformResultPublisher } from "./aws-adapters";
import { ImageTransformWorker } from "./worker";

export interface MessageProcessor {
  processMessage(body: string): Promise<void>;
}

let defaultWorker: ImageTransformWorker | undefined;

export async function handler(event: SQSEvent): Promise<SQSBatchResponse> {
  return handleSqsEvent(event, getDefaultWorker());
}

export async function handleSqsEvent(
  event: SQSEvent,
  processor: MessageProcessor,
): Promise<SQSBatchResponse> {
  const batchItemFailures: SQSBatchResponse["batchItemFailures"] = [];
  for (const record of event.Records) {
    try {
      await processor.processMessage(record.body);
    } catch (error) {
      logSafeFailure(record, error);
      batchItemFailures.push({ itemIdentifier: record.messageId });
    }
  }
  return { batchItemFailures };
}

function getDefaultWorker(): ImageTransformWorker {
  if (defaultWorker !== undefined) {
    return defaultWorker;
  }

  const originalBucket = requiredEnvironment("MEDIA_ORIGINAL_BUCKET");
  const deliveryBucket = requiredEnvironment("MEDIA_DELIVERY_BUCKET");
  const resultQueueUrl = requiredEnvironment("MEDIA_RESULT_QUEUE_URL");
  defaultWorker = new ImageTransformWorker(
    new AwsImageObjectStorage(originalBucket, deliveryBucket),
    new SqsTransformResultPublisher(resultQueueUrl),
  );
  return defaultWorker;
}

function requiredEnvironment(name: string): string {
  const value = process.env[name];
  if (value === undefined || value.trim().length === 0) {
    throw new Error(`Required worker environment is missing: ${name}`);
  }
  return value;
}

function logSafeFailure(record: SQSRecord, error: unknown): void {
  const errorName = error instanceof Error ? error.name : "UnknownError";
  console.error(
    JSON.stringify({
      event: "image_transform_record_failed",
      errorName,
      messageId: record.messageId,
    }),
  );
}
