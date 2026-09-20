package org.sopt.hashi.media.internal.metrics;

import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Locale;
import org.sopt.hashi.media.internal.queue.MediaRenditionResult;
import org.sopt.hashi.media.internal.queue.MediaTransformFailedResult;
import org.sopt.hashi.media.internal.queue.MediaTransformResult;
import org.sopt.hashi.media.internal.queue.MediaTransformSucceededResult;
import org.sopt.hashi.media.service.MediaTransformResultApplication;
import org.sopt.hashi.media.service.MediaTransformResultDisposition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class MediaPipelineMetrics {

    private static final Logger LOGGER = LoggerFactory.getLogger(MediaPipelineMetrics.class);
    private static final String REQUEST_METRIC = "hashi.media.transform.request";
    private static final String RESULT_METRIC = "hashi.media.transform.result";
    private static final String FAILURE_METRIC = "hashi.media.transform.failure";
    private static final String PROCESSING_DURATION_METRIC =
            "hashi.media.transform.processing.duration";
    private static final String RENDITION_BYTES_METRIC = "hashi.media.rendition.bytes";
    private static final String RENDITION_RATIO_METRIC = "hashi.media.rendition.source.ratio";
    private static final String RECOVERY_METRIC = "hashi.media.recovery.request";
    private static final String EPR_METRIC = "hashi.media.epr.resubmit";

    private final MeterRegistry meterRegistry;

    public MediaPipelineMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public void recordRequest(String outcome) {
        recordSafely(() ->
                meterRegistry.counter(REQUEST_METRIC, "outcome", outcome).increment());
    }

    public void recordResult(
            MediaTransformResult result,
            MediaTransformResultApplication application
    ) {
        recordSafely(() -> recordResultMeters(result, application));
    }

    private void recordResultMeters(
            MediaTransformResult result,
            MediaTransformResultApplication application
    ) {
        String status = resultStatus(result);
        String outcome = application.disposition().name().toLowerCase(Locale.ROOT);
        meterRegistry.counter(RESULT_METRIC, "status", status, "outcome", outcome).increment();
        if (application.disposition() != MediaTransformResultDisposition.APPLIED) {
            return;
        }
        meterRegistry.timer(PROCESSING_DURATION_METRIC, "status", status)
                .record(application.processingDuration());

        if (result instanceof MediaTransformFailedResult failed) {
            meterRegistry.counter(
                    FAILURE_METRIC,
                    "failure_code",
                    failed.failureCode().name().toLowerCase(Locale.ROOT)
            ).increment();
            return;
        }

        MediaTransformSucceededResult succeeded = (MediaTransformSucceededResult) result;
        for (MediaRenditionResult rendition : succeeded.renditions()) {
            String role = rendition.role().name().toLowerCase(Locale.ROOT);
            DistributionSummary.builder(RENDITION_BYTES_METRIC)
                    .baseUnit("bytes")
                    .tag("role", role)
                    .register(meterRegistry)
                    .record(rendition.byteSize());
            DistributionSummary.builder(RENDITION_RATIO_METRIC)
                    .baseUnit("ratio")
                    .tag("role", role)
                    .register(meterRegistry)
                    .record((double) rendition.byteSize()
                            / succeeded.verifiedSource().byteSize());
        }
    }

    public void recordResultContractError() {
        recordSafely(() -> recordResultError("unknown", "contract_error"));
    }

    public void recordResultContractError(MediaTransformResult result) {
        recordSafely(() ->
                recordResultError(resultStatus(result), "contract_error"));
    }

    public void recordResultInternalError(MediaTransformResult result) {
        recordSafely(() ->
                recordResultError(resultStatus(result), "internal_error"));
    }

    private void recordResultError(String status, String outcome) {
        meterRegistry.counter(
                RESULT_METRIC, "status", status, "outcome", outcome).increment();
    }

    private String resultStatus(MediaTransformResult result) {
        return result instanceof MediaTransformSucceededResult ? "succeeded" : "failed";
    }

    public void recordRecovery(String outcome) {
        recordSafely(() ->
                meterRegistry.counter(RECOVERY_METRIC, "outcome", outcome).increment());
    }

    public void recordEprResubmission(String trigger) {
        recordSafely(() ->
                meterRegistry.counter(EPR_METRIC, "trigger", trigger).increment());
    }

    private void recordSafely(Runnable recorder) {
        try {
            recorder.run();
        } catch (RuntimeException e) {
            LOGGER.warn("Failed to record a media pipeline metric", e);
        }
    }
}
