package com.emc.mongoose.base.load.step.client;

import com.emc.mongoose.base.env.FsUtil;
import com.emc.mongoose.base.load.step.file.FileManager;
import com.emc.mongoose.base.load.step.service.file.FileManagerService;
import com.emc.mongoose.base.logging.LogContextThreadFactory;
import com.emc.mongoose.base.logging.LogUtil;
import com.emc.mongoose.base.logging.Loggers;
import com.github.akurilov.commons.system.SizeInBytes;
import com.github.akurilov.confuse.Config;
import org.apache.logging.log4j.Level;

import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import static com.emc.mongoose.base.Constants.KEY_CLASS_NAME;
import static com.emc.mongoose.base.Exceptions.throwUncheckedIfInterrupted;
import static com.emc.mongoose.base.load.step.client.LoadStepClient.OUTPUT_PROGRESS_PERIOD_MILLIS;
import static com.github.akurilov.commons.lang.Exceptions.throwUnchecked;
import static org.apache.logging.log4j.CloseableThreadContext.put;

// unlike other aggregators this one pulls each remote tmp timing metrics (latency and duration) file
// into a separate local file.
// This is done to increase performance as mock tests showed that sorting 4 files 25mil lines each
// and then mergeSorting them is 2+ times faster than sorting 100mil file using Collections.sort().
public class ItemTimingMetricOutputFileAggregator implements AutoCloseable {

    private final String loadStepId;
    private final Path itemTimingMetricsOutputFilePath;
    private final Map<FileManager, String> itemTimingMetricOutputFileSlices;

    public ItemTimingMetricOutputFileAggregator(
            final String loadStepId,
            final List<FileManager> fileMgrs) {
        this.loadStepId = loadStepId;
        final var sliceCount = fileMgrs.size();
        this.itemTimingMetricOutputFileSlices = new HashMap<>(sliceCount);
        this.itemTimingMetricsOutputFilePath = Paths.get(System.getProperty("java.io.tmpdir"),
                "mongoose", "timingMetrics_" + loadStepId);

        // file manager for slice == 0 is the file manager of the entry node. Shouldn't be a service.
        if (fileMgrs.get(0) instanceof FileManagerService) {
            throw new AssertionError("File manager @ index #0 shouldn't be a service");
        }
        itemTimingMetricOutputFileSlices.put(fileMgrs.get(0), itemTimingMetricsOutputFilePath.toString());

        for (var i = 1; i < sliceCount; i++) {
            final var fileMgr = fileMgrs.get(i);
            if (fileMgr instanceof FileManagerService) {
                try {
                    itemTimingMetricOutputFileSlices.put(fileMgr, itemTimingMetricsOutputFilePath.toString());
                    Loggers.MSG.debug(
                            "\"{}\": new tmp item output file \"{}\"", fileMgr, itemTimingMetricsOutputFilePath.toString());
                } catch (final Exception e) {
                    throwUncheckedIfInterrupted(e);
                    LogUtil.exception(
                            Level.ERROR,
                            e,
                            "Failed to get the new temporary file name for the file manager service \"{}\"",
                            fileMgr);
                }
            } else {
                throw new AssertionError("File manager @ index #" + i + " should be a service");
            }

        }
    }

    // each remote file is collected to a local file that has the same old name + the random number
    private void collectToLocal() {
        final var byteCounter = new LongAdder();
        final var executor = Executors.newScheduledThreadPool(
                2, new LogContextThreadFactory("collectItemTimingMetricsOutputFileWorker", true));
        // we need as many locks as there are slices except for entry node. Because we have a separate tmp file for each slice
        final var finishLatch = new CountDownLatch(itemTimingMetricOutputFileSlices.size() - 1);
        FsUtil.createParentDirsIfNotExist(itemTimingMetricsOutputFilePath);
        executor.submit(
                () -> {
                        itemTimingMetricOutputFileSlices
                                .entrySet()
                                .parallelStream()
                                // don't transfer & delete local item output file
                                .filter(entry -> entry.getKey() instanceof FileManagerService)
                                .forEach(
                                        entry -> {
                                            final var fileMgr = entry.getKey();
                                            final var remoteItemOutputFileName = entry.getValue();
                                            final Path localItemOutputPath = Paths.get(itemTimingMetricsOutputFilePath.toString() +
                                                    "_" + (new Random()).nextLong());
                                            try (final var localItemOutput =
                                                         Files.newOutputStream(localItemOutputPath,
                                                                 FileManager.APPEND_OPEN_OPTIONS)) {
                                            transferToLocal(
                                                    fileMgr,
                                                    remoteItemOutputFileName,
                                                    localItemOutput,
                                                    byteCounter);
                                            } catch (final IOException e) {
                                                LogUtil.exception(
                                                        Level.WARN,
                                                        e,
                                                        "{}: failed to open the local item output file \"{}\" for appending",
                                                        loadStepId,
                                                        itemTimingMetricsOutputFilePath.toString());
                                            } finally {
                                                finishLatch.countDown();
                                            }
                                            try {
                                                fileMgr.deleteFile(remoteItemOutputFileName);
                                            } catch (final Exception e) {
                                                throwUncheckedIfInterrupted(e);
                                                LogUtil.exception(
                                                        Level.WARN,
                                                        e,
                                                        "{}: failed to delete the file \"{}\" @ file manager \"{}\"",
                                                        loadStepId,
                                                        remoteItemOutputFileName,
                                                        fileMgr);
                                            }
                                        });
                });
        executor.scheduleAtFixedRate(
                () -> Loggers.MSG.info(
                        "\"{}\" <- transferred {} of the output items data...",
                        itemTimingMetricsOutputFilePath.toString(),
                        SizeInBytes.formatFixedSize(byteCounter.longValue())),
                0,
                OUTPUT_PROGRESS_PERIOD_MILLIS,
                TimeUnit.MILLISECONDS);

        try {
            finishLatch.await();
        } catch (final InterruptedException e) {
            throwUnchecked(e);
        } finally {
            executor.shutdownNow();
            Loggers.MSG.info(
                    "\"{}\" <- transferred {} of the output items data",
                    itemTimingMetricsOutputFilePath.toString(),
                    SizeInBytes.formatFixedSize(byteCounter.longValue()));
        }
    }

    private static void transferToLocal(
            final FileManager fileMgr,
            final String remoteItemOutputFileName,
            final OutputStream localItemOutput,
            final LongAdder byteCounter) {
        long transferredByteCount = 0;
        try (final var logCtx = put(KEY_CLASS_NAME, ItemOutputFileAggregator.class.getSimpleName())) {
            byte buff[];
            while (true) {
                buff = fileMgr.readFromFile(remoteItemOutputFileName, transferredByteCount);
                localItemOutput.write(buff);
                transferredByteCount += buff.length;
                byteCounter.add(buff.length);
            }
        } catch (final EOFException ok) {
        } catch (final IOException e) {
            LogUtil.exception(Level.WARN, e, "Remote items output file transfer failure");
        } finally {
            Loggers.MSG.debug(
                    "{} of items output data transferred from \"{}\" @ \"{}\" to \"{}\"",
                    SizeInBytes.formatFixedSize(transferredByteCount),
                    remoteItemOutputFileName,
                    fileMgr,
                    localItemOutput);
        }
    }

    @Override
    public final void close() {
        Loggers.MSG.info("closing metrics aggregator");
        try {
            collectToLocal();
        } finally {
            itemTimingMetricOutputFileSlices.clear();
        }
    }
}
