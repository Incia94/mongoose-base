package com.emc.mongoose.base.load.step.client;

import static com.emc.mongoose.base.Constants.KEY_CLASS_NAME;
import static com.emc.mongoose.base.Constants.KEY_STEP_ID;
import static com.emc.mongoose.base.Exceptions.throwUncheckedIfInterrupted;
import static com.emc.mongoose.base.config.ConfigUtil.flatten;
import static com.github.akurilov.commons.lang.Exceptions.throwUnchecked;
import static org.apache.logging.log4j.CloseableThreadContext.put;

import com.emc.mongoose.base.config.AliasingUtil;
import com.emc.mongoose.base.data.DataInput;
import com.emc.mongoose.base.env.Extension;
import com.emc.mongoose.base.config.IllegalConfigurationException;
import com.emc.mongoose.base.item.io.ItemInputFactory;
import com.emc.mongoose.base.item.op.OpType;
import com.emc.mongoose.base.load.step.LoadStep;
import com.emc.mongoose.base.load.step.LoadStepBase;
import com.emc.mongoose.base.load.step.LoadStepFactory;
import com.emc.mongoose.base.load.step.client.metrics.MetricsAggregator;
import com.emc.mongoose.base.load.step.client.metrics.MetricsAggregatorImpl;
import com.emc.mongoose.base.load.step.file.FileManager;
import com.emc.mongoose.base.logging.LogUtil;
import com.emc.mongoose.base.logging.Loggers;
import com.emc.mongoose.base.metrics.MetricsManager;
import com.emc.mongoose.base.metrics.context.DistributedMetricsContext;
import com.emc.mongoose.base.metrics.context.DistributedMetricsContextImpl;
import com.emc.mongoose.base.metrics.snapshot.AllMetricsSnapshot;
import com.emc.mongoose.base.storage.driver.StorageDriver;
import com.github.akurilov.commons.net.NetUtil;
import com.github.akurilov.commons.reflection.TypeUtil;
import com.github.akurilov.commons.system.SizeInBytes;
import com.github.akurilov.confuse.Config;
import com.github.akurilov.confuse.exceptions.InvalidValueTypeException;
import com.github.akurilov.confuse.impl.BasicConfig;

import java.io.IOException;
import java.rmi.RemoteException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.apache.logging.log4j.Level;

public abstract class LoadStepClientBase
extends LoadStepBase
implements LoadStepClient {

	private final List<LoadStep> stepSlices = new ArrayList<>();
	private final List<FileManager> fileMgrs = new ArrayList<>();
	// for the core configuration options which are using the files
	private final List<AutoCloseable> itemDataInputFileSlicers = new ArrayList<>();
	private final List<AutoCloseable> itemInputFileSlicers = new ArrayList<>();
	private final List<AutoCloseable> itemOutputFileAggregators = new ArrayList<>();
	private final List<AutoCloseable> itemTimingMetricsOutputFileAggregators = new ArrayList<>();
	private final List<AutoCloseable> opTraceLogFileAggregators = new ArrayList<>();
	private final List<AutoCloseable> storageAuthFileSlicers = new ArrayList<>();

	public LoadStepClientBase(
		final Config config, final List<Extension> extensions, final List<Config> ctxConfigs,
		final MetricsManager metricsMgr
	) {
		super(config, extensions, ctxConfigs, metricsMgr);
	}

	private MetricsAggregator metricsAggregator = null;

	@Override
	protected final void doStartWrapped()
	throws IllegalArgumentException {
		try(final var logCtx = put(KEY_STEP_ID, loadStepId()).put(KEY_CLASS_NAME, getClass().getSimpleName())) {
			// need to set the once generated step id
			config.val("load-step-id", loadStepId());
			config.val("load-step-idAutoGenerated", false);
			final var nodeAddrs = remoteNodeAddrs(config);
			initFileManagers(nodeAddrs, fileMgrs);
			final var sliceCount = 1 + nodeAddrs.size();
			// init the base/shared config slices
			final var configSlices = sliceConfig(config, sliceCount);
			addFileClients(config, configSlices);
			// init the config slices for each of the load step context configs
			final var ctxConfigsSlices = (List<List<Config>>) new ArrayList<List<Config>>(sliceCount);
			for(var i = 0; i < sliceCount; i++) {
				ctxConfigsSlices.add(new ArrayList<>());
			}
			if(null != ctxConfigs) {
				for(final var ctxConfig : ctxConfigs) {
					final var ctxConfigSlices = sliceConfig(ctxConfig, sliceCount);
					addFileClients(ctxConfig, ctxConfigSlices);
					for(var i = 0; i < sliceCount; i++) {
						ctxConfigsSlices.get(i).add(ctxConfigSlices.get(i));
					}
				}
			}
			initAndStartStepSlices(nodeAddrs, configSlices, ctxConfigsSlices, metricsMgr);
			initAndStartMetricsAggregator();
			Loggers.MSG.info(
				"{}: load step client started, additional nodes: {}", loadStepId(),
				Arrays.toString(nodeAddrs.toArray())
			);
		}
	}

	// determine the additional/remote full node addresses
	private static List<String> remoteNodeAddrs(final Config config) {
		final var nodeConfig = config.configVal("load-step-node");
		final var nodePort = nodeConfig.intVal("port");
		final var nodeAddrs = nodeConfig.<String>listVal("addrs");
		return nodeAddrs == null || nodeAddrs.isEmpty() ? Collections.EMPTY_LIST :
			   nodeAddrs.stream().map(addr -> NetUtil.addPortIfMissing(addr, nodePort)).collect(Collectors.toList());
	}

	private static void initFileManagers(final List<String> nodeAddrs, final List<FileManager> fileMgrsDst) {
		// local file manager
		fileMgrsDst.add(FileManager.INSTANCE);
		// remote file managers
		nodeAddrs.stream().map(FileManagerClient::resolve).forEachOrdered(fileMgrsDst::add);
	}

	private void addFileClients(final Config config, final List<Config> configSlices) {
		final var loadConfig = config.configVal("load");
		final var batchSize = loadConfig.intVal("batch-size");
		final var storageConfig = config.configVal("storage");
		final var itemConfig = config.configVal("item");
		final var itemDataConfig = itemConfig.configVal("data");
		final var verifyFlag = itemDataConfig.boolVal("verify");
		final var itemDataInputConfig = itemDataConfig.configVal("input");
		final var itemDataInputLayerConfig = itemDataInputConfig.configVal("layer");
		final var itemDataInputLayerSizeRaw = itemDataInputLayerConfig.val("size");
		final SizeInBytes itemDataLayerSize;
		if(itemDataInputLayerSizeRaw instanceof String) {
			itemDataLayerSize = new SizeInBytes((String) itemDataInputLayerSizeRaw);
		} else {
			itemDataLayerSize = new SizeInBytes(TypeUtil.typeConvert(itemDataInputLayerSizeRaw, int.class));
		}
		final var itemDataInputFile = itemDataInputConfig.stringVal("file");
		final var itemDataInputSeed = itemDataInputConfig.stringVal("seed");
		final var itemDataInputLayerCacheSize = itemDataInputLayerConfig.intVal("cache");
		final var isInHeapMem = itemDataInputLayerConfig.boolVal("heap");
		try(
			final var dataInput = DataInput.instance(
				itemDataInputFile, itemDataInputSeed, itemDataLayerSize, itemDataInputLayerCacheSize, isInHeapMem
			);
			final var storageDriver = StorageDriver.instance(
				extensions, storageConfig, dataInput, verifyFlag, batchSize, loadStepId()
			);
			final var itemInput = ItemInputFactory.createItemInput(itemConfig, batchSize, storageDriver)
		) {
			if(null != itemDataInputFile && ! itemDataInputFile.isEmpty()) {
				itemDataInputFileSlicers.add(
					new ItemDataInputFileSlicer(loadStepId(), fileMgrs, configSlices, itemDataInputFile, batchSize)
				);
				Loggers.MSG.debug("{}: item data input file slicer initialized", loadStepId());
			}
			if(null != itemInput) {
				itemInputFileSlicers.add(
					new ItemInputFileSlicer(loadStepId(), fileMgrs, configSlices, itemInput, batchSize)
				);
				Loggers.MSG.debug("{}: item input file slicer initialized", loadStepId());
			}
		} catch(final IllegalConfigurationException e) {
			LogUtil.exception(Level.ERROR, e, "{}: failed to init the storage driver", loadStepId());
		} catch(final InterruptedException e) {
			throwUnchecked(e);
		} catch(final Exception e) {
			LogUtil.exception(Level.WARN, e, "{}: failed to close the item input", loadStepId());
		}
		final var itemOutputFile = config.stringVal("item-output-file");
		if(itemOutputFile != null && ! itemOutputFile.isEmpty()) {
			itemOutputFileAggregators.add(
				new ItemOutputFileAggregator(loadStepId(), fileMgrs, configSlices, itemOutputFile));
			Loggers.MSG.debug("{}: item output file aggregator initialized", loadStepId());
		}
		/*final var itemTimingMetricsOutputFilePath = config.stringVal("item-output-metrics-file");
		final Input<String> itemTimingMetricsOutputFileInput;
		if (itemTimingMetricsOutputFilePath.contains(ASYNC_MARKER) ||
				itemTimingMetricsOutputFilePath.contains(SYNC_MARKER) ||
				itemTimingMetricsOutputFilePath.contains(INIT_MARKER)) {
			itemTimingMetricsOutputFileInput = CompositeExpressionInputBuilder.newInstance()
					.expression(itemTimingMetricsOutputFilePath)
					.build();
		} else {
			itemTimingMetricsOutputFileInput = new ConstantValueInputImpl<>(itemTimingMetricsOutputFilePath);
		}*/

		itemTimingMetricsOutputFileAggregators.add(
				new ItemTimingMetricOutputFileAggregator(loadStepId(), fileMgrs, configSlices/*, itemTimingMetricsOutputFileInput.get()*/));
		Loggers.MSG.debug("{}: item metrics output file aggregator initialized", loadStepId());

		if(config.boolVal("output-metrics-trace-persist")) {
			opTraceLogFileAggregators.add(new OpTraceLogFileAggregator(loadStepId(), fileMgrs));
			Loggers.MSG.debug("{}: operation traces log file aggregator initialized", loadStepId());
		}
		final var storageAuthFile = storageConfig.stringVal("auth-file");
		if(storageAuthFile != null && ! storageAuthFile.isEmpty()) {
			storageAuthFileSlicers.add(
				new TempInputTextFileSlicer(
					loadStepId(), storageAuthFile, fileMgrs, "storage-auth-file", configSlices, batchSize
				)
			);
			Loggers.MSG.debug("{}: storage auth file slicer initialized", loadStepId());
		}
	}

	private void initAndStartMetricsAggregator() {
		try(final var logCtx = put(KEY_STEP_ID, loadStepId()).put(KEY_CLASS_NAME, getClass().getSimpleName())) {
			metricsAggregator = new MetricsAggregatorImpl(loadStepId(), stepSlices);
			metricsAggregator.start();
		} catch(final Exception e) {
			LogUtil.exception(Level.ERROR, e, "{}: failed to start the metrics aggregator", loadStepId());
		}
	}

	private void initAndStartStepSlices(
		final List<String> nodeAddrs, final List<Config> configSlices, final List<List<Config>> ctxConfigsSlices,
		final MetricsManager metricsManager
	) {
		final String stepTypeName;
		try {
			stepTypeName = getTypeName();
		} catch(final RemoteException e) {
			throw new AssertionError(e);
		}
		final var sliceCount = configSlices.size();
		for(var i = 0; i < sliceCount; i++) {
			final var configSlice = configSlices.get(i);
			final LoadStep stepSlice;
			if(i == 0) {
				stepSlice = LoadStepFactory.createLocalLoadStep(
					configSlice, extensions, ctxConfigsSlices.get(i), metricsManager, stepTypeName
				);
			} else {
				final var nodeAddrWithPort = nodeAddrs.get(i - 1);
				stepSlice = LoadStepSliceUtil.resolveRemote(
					configSlice, ctxConfigsSlices.get(i), stepTypeName, nodeAddrWithPort
				);
			}
			stepSlices.add(stepSlice);
			if(stepSlice != null) {
				try {
					stepSlice.start();
				} catch(final Exception e) {
					if(e instanceof InterruptedException) {
						throwUnchecked(e);
					}
					LogUtil.exception(
						Level.ERROR, e, "{}: failed to start the step slice \"{}\"", loadStepId(), stepSlice
					);
				}
			}
		}
	}

	private List<Config> sliceConfig(final Config config, final int sliceCount) {
		final var configSlices = (List<Config>) new ArrayList<Config>(sliceCount);
		for(var i = 0; i < sliceCount; i++) {
			final var configSlice = ConfigSliceUtil.initSlice(config);
			if(i == 0) {
				// local step slice: disable the average metrics output
				configSlice.val("output-metrics-average-period", "0s");
			}
			configSlices.add(configSlice);
		}
		if(sliceCount > 1) { // distributed mode
			//
			final var countLimit = config.longVal("load-op-limit-count");
			if(countLimit > 0) {
				ConfigSliceUtil.sliceLongValue(countLimit, configSlices, "load-op-limit-count");
				configSlices
					.stream()
					.mapToLong(configSlice -> configSlice.longVal("load-op-limit-count"))
					.filter(countLimitSlice -> countLimitSlice == 0)
					.findAny()
					.ifPresent(
						countLimitSlice -> Loggers.MSG.fatal(
							"{}: the count limit ({}) is too small to be sliced among the {} nodes, the load step " +
							"won't work correctly", loadStepId(), countLimit, sliceCount
						)
					);
			}
			//
			final var countFailLimit = config.longVal("load-op-limit-fail-count");
			if(countFailLimit > 0) {
				ConfigSliceUtil.sliceLongValue(countFailLimit, configSlices, "load-op-limit-fail-count");
				configSlices
					.stream()
					.mapToLong(configSlice -> configSlice.longVal("load-op-limit-fail-count"))
					.filter(failCountLimitSlice -> failCountLimitSlice == 0)
					.findAny()
					.ifPresent(
						failCountLimitSlice -> Loggers.MSG.error(
							"{}: the failures count limit ({}) is too small to be sliced among the {} nodes, the load " +
							"step may not work correctly", loadStepId(), countLimit, sliceCount
						)
					);
			}
			//
			final var rateLimit = config.doubleVal("load-op-limit-rate");
			if(rateLimit > 0) {
				ConfigSliceUtil.sliceDoubleValue(rateLimit, configSlices, "load-op-limit-rate");
			}
			//
			final long sizeLimit;
			final var sizeLimitRaw = config.val("load-step-limit-size");
			if(sizeLimitRaw instanceof String) {
				sizeLimit = SizeInBytes.toFixedSize((String) sizeLimitRaw);
			} else {
				sizeLimit = TypeUtil.typeConvert(sizeLimitRaw, long.class);
			}
			if(sizeLimit > 0) {
				ConfigSliceUtil.sliceLongValue(sizeLimit, configSlices, "load-step-limit-size");
			}
			//
			try {
				final var storageNetNodeConfig = config.configVal("storage-net-node");
				final var sliceStorageNodesFlag = storageNetNodeConfig.boolVal("slice");
				if(sliceStorageNodesFlag) {
					final var storageNodeAddrs = storageNetNodeConfig.<String>listVal("addrs");
					ConfigSliceUtil.sliceStorageNodeAddrs(configSlices, storageNodeAddrs);
				}
			} catch(final NoSuchElementException ignore) {
			} catch(final InvalidValueTypeException e) {
				if(null != e.actualType()) {
					LogUtil.exception(Level.ERROR, e, "Failed to assign the storage endpoints to the nodes");
				}
			}
			//
			ConfigSliceUtil.sliceItemNaming(configSlices);
		}
		return configSlices;
	}

	private int sliceCount() {
		return stepSlices.size();
	}

	protected final void initMetrics(
		final int originIndex, final OpType opType, final int concurrencyLimit, final Config metricsConfig,
		final SizeInBytes itemDataSize, final boolean outputColorFlag
	) {
		final var concurrencyThreshold = (int) (concurrencyLimit * metricsConfig.doubleVal("threshold"));
		final var metricsAvgPersistFlag = metricsConfig.boolVal("average-persist");
		final var metricsSumPersistFlag = metricsConfig.boolVal("summary-persist");
		// it's not known yet how many nodes are involved, so passing the function "this::sliceCount"
		// reference for
		// further usage
		final var metricsCtx = (DistributedMetricsContext) DistributedMetricsContextImpl
			.builder()
			.loadStepId(loadStepId())
			.opType(opType)
			.nodeCountSupplier(this::sliceCount)
			.concurrencyLimit(concurrencyLimit)
			.concurrencyThreshold(concurrencyThreshold)
			.itemDataSize(itemDataSize)
			.outputPeriodSec(avgPeriod(metricsConfig))
			.stdOutColorFlag(outputColorFlag)
			.avgPersistFlag(metricsAvgPersistFlag)
			.sumPersistFlag(metricsSumPersistFlag)
			.snapshotsSupplier(() -> metricsSnapshotsByIndex(originIndex))
			.quantileValues(quantiles(metricsConfig))
			.nodeAddrs(remoteNodeAddrs(config))
			.comment(config.stringVal("run-comment"))
			.runId(runId())
			.build();
		metricsContexts.add(metricsCtx);
	}

	private List<Double> quantiles(final Config metricsConfig) {
		List<Double> quantileValues = metricsConfig
				.listVal("quantiles")
				.stream()
				.map(v -> {
					Double val = Double.valueOf(v.toString());
					if ((val < 0) || (val >= 1)) {
						throw new IllegalArgumentException("Quantile values must be in range [0,1), but found" + val);
					}
					return val;
				})
				.collect(Collectors.toList());
		if (quantileValues.size() == 0) {
			throw new IllegalArgumentException("Quantile values list cannot be empty");
		}
		return quantileValues;
	}

	private List<AllMetricsSnapshot> metricsSnapshotsByIndex(final int originIndex) {
		return metricsAggregator == null ?
			Collections.emptyList() :
			metricsAggregator.metricsSnapshotsByIndex(originIndex);
	}

	@Override
	protected final void doShutdown() {
		stepSlices.parallelStream().forEach(stepSlice -> {
			try(final var logCtx = put(KEY_STEP_ID, loadStepId()).put(KEY_CLASS_NAME, getClass().getSimpleName())) {
				stepSlice.shutdown();
			} catch(final RemoteException e) {
				LogUtil.exception(Level.WARN, e, "{}: failed to shutdown the step service {}", loadStepId(), stepSlice);
			}
		});
	}

	@Override
	public final boolean await(final long timeout, final TimeUnit timeUnit)
	throws IllegalStateException, InterruptedException {
		final var stepSliceCount = stepSlices.size();
		try(final var logCtx = put(KEY_STEP_ID, loadStepId()).put(KEY_CLASS_NAME, getClass().getSimpleName())) {
			if(0 == stepSliceCount) {
				throw new IllegalStateException("No step slices are available");
			}
			Loggers.MSG.debug(
				"{}: await for {} step slices for at most {} {}...", loadStepId(), stepSliceCount,
				timeout, timeUnit.name().toLowerCase()
			);
			return stepSlices.parallelStream().map(stepSlice -> {
				try {
					final var invokeTimeMillis = System.currentTimeMillis();
					final var timeOutMillis = timeUnit.toMillis(timeout);
					var awaitResult = false;
					while(timeOutMillis > System.currentTimeMillis() - invokeTimeMillis) {
						if(Thread.currentThread().isInterrupted()) {
							throwUnchecked(new InterruptedException());
						}
						awaitResult = stepSlice.await(1, TimeUnit.MILLISECONDS);
						if (awaitResult) { // awaitResult = (0 == countDown)
							break;
						}
					}
					return awaitResult;
				} catch(final InterruptedException e) {
					throwUnchecked(e);
				} catch(final RemoteException e) {
					return false;
				}
				return false;
			}).reduce((flag1, flag2) -> flag1 && flag2).orElse(false);
		} finally {
			Loggers.MSG.info("{}: await for {} step slices done", loadStepId(), stepSliceCount);
			doStop();
		}
	}

	@Override
	protected final void doStop() {
		stepSlices.parallelStream().forEach(stepSlice -> {
			try(
				final var logCtx = put(KEY_STEP_ID, stepSlice.loadStepId()).put(
					KEY_CLASS_NAME, getClass().getSimpleName())
			) {
				stepSlice.stop();
			} catch(final Exception e) {
				throwUncheckedIfInterrupted(e);
				LogUtil.trace(Loggers.ERR, Level.WARN, e, "{}: failed to stop the step slice \"{}\"", loadStepId(),
					stepSlice
				);
			}
		});
		if(null != metricsAggregator) {
			try {
				metricsAggregator.stop();
			} catch(final RemoteException ignored) {
			}
		}
		itemTimingMetricsOutputFileAggregators.parallelStream().forEach(itemMetricsOutputFileAggregator -> {
			try {
				itemMetricsOutputFileAggregator.close();
			} catch(final Exception e) {
				throwUncheckedIfInterrupted(e);
				LogUtil.exception(Level.WARN, e, "{}: failed to close the item metrics output file aggregator \"{}\"",
						loadStepId(), itemMetricsOutputFileAggregator
				);
			}
		});
		itemTimingMetricsOutputFileAggregators.clear();
		super.doStop();
	}

	@Override
	protected final void doClose()
	throws IOException {
		try(final var logCtx = put(KEY_STEP_ID, loadStepId()).put(KEY_CLASS_NAME, getClass().getSimpleName())) {
			super.doClose();
			if(null != metricsAggregator) {
				metricsAggregator.close();
				metricsAggregator = null;
			}
			stepSlices.parallelStream().forEach(stepSlice -> {
				try {
					stepSlice.close();
					Loggers.MSG.debug("{}: step slice \"{}\" closed", loadStepId(), stepSlice);
				} catch(final Exception e) {
					throwUncheckedIfInterrupted(e);
					LogUtil.exception(Level.WARN, e, "{}: failed to close the step service \"{}\"", loadStepId(),
						stepSlice
					);
				}
			});
			Loggers.MSG.debug("{}: closed all {} step slices", loadStepId(), stepSlices.size());
			stepSlices.clear();
			itemDataInputFileSlicers.forEach(itemDataInputFileSlicer -> {
				try {
					itemDataInputFileSlicer.close();
				} catch(final Exception e) {
					throwUncheckedIfInterrupted(e);
					LogUtil.exception(Level.WARN, e, "{}: failed to close the item data input file slicer \"{}\"",
						loadStepId(), itemDataInputFileSlicer
					);
				}
			});
			itemDataInputFileSlicers.clear();
			itemInputFileSlicers.forEach(itemInputFileSlicer -> {
				try {
					itemInputFileSlicer.close();
				} catch(final Exception e) {
					throwUncheckedIfInterrupted(e);
					LogUtil.exception(Level.WARN, e, "{}: failed to close the item input file slicer \"{}\"",
						loadStepId(), itemInputFileSlicer
					);
				}
			});
			itemInputFileSlicers.clear();
			itemOutputFileAggregators.parallelStream().forEach(itemOutputFileAggregator -> {
				try {
					itemOutputFileAggregator.close();
				} catch(final Exception e) {
					throwUncheckedIfInterrupted(e);
					LogUtil.exception(Level.WARN, e, "{}: failed to close the item output file aggregator \"{}\"",
						loadStepId(), itemOutputFileAggregator
					);
				}
			});
			itemOutputFileAggregators.clear();
			opTraceLogFileAggregators.parallelStream().forEach(opTraceLogFileAggregator -> {
				try {
					opTraceLogFileAggregator.close();
				} catch(final Exception e) {
					throwUncheckedIfInterrupted(e);
					LogUtil.exception(Level.WARN, e,
						"{}: failed to close the operation traces log file aggregator \"{}\"", loadStepId(),
						opTraceLogFileAggregator
					);
				}
			});
			opTraceLogFileAggregators.clear();
			storageAuthFileSlicers.forEach(storageAuthFileSlicer -> {
				try {
					storageAuthFileSlicer.close();
				} catch(final Exception e) {
					throwUncheckedIfInterrupted(e);
					LogUtil.exception(Level.WARN, e, "{}: failed to close the storage auth file slicer \"{}\"",
						loadStepId(), storageAuthFileSlicer
					);
				}
			});
			storageAuthFileSlicers.clear();
		}
	}

	@Override
	public final <T extends LoadStepClient> T config(final Map<String, Object> configMap) {
		if(ctxConfigs != null) {
			throw new IllegalStateException("config(...) should be invoked before any append(...) call");
		}
		final var configCopy = (Config) new BasicConfig(config);
		final var argValPairs = (Map<String, String>) new HashMap<String, String>();
		flatten(configMap, argValPairs, config.pathSep(), null);
		final var aliasingConfig = config.<Map<String, Object>>listVal("aliasing");
		try {
			final var aliasedArgs = AliasingUtil.apply(argValPairs, aliasingConfig);
			if(config.boolVal("load-step-idAutoGenerated")) {
				if(aliasedArgs.get("load-step-id") != null) {
					configCopy.val("load-step-idAutoGenerated", false);
				}
			}
			aliasedArgs.forEach(configCopy::val); // merge
		} catch(final Exception e) {
			LogUtil.exception(Level.FATAL, e, "Scenario syntax error");
			throwUnchecked(e);
		}
		return copyInstance(configCopy, null);
	}

	@Override
	public final <T extends LoadStepClient> T append(final Map<String, Object> context) {
		final List<Config> ctxConfigsCopy;
		if(ctxConfigs == null) {
			ctxConfigsCopy = new ArrayList<>(1);
		} else {
			ctxConfigsCopy = ctxConfigs.stream().map(BasicConfig::new).collect(Collectors.toList());
		}
		final var argValPairs = (Map<String, String>) new HashMap<String, String>();
		flatten(context, argValPairs, config.pathSep(), null);
		final var aliasingConfig = config.<Map<String, Object>>listVal("aliasing");
		final var ctxConfig = (Config) new BasicConfig(config);
		try {
			final var aliasedArgs = AliasingUtil.apply(argValPairs, aliasingConfig);
			aliasedArgs.forEach(ctxConfig::val); // merge
		} catch(final Exception e) {
			LogUtil.exception(Level.FATAL, e, "Scenario syntax error");
			throwUnchecked(e);
		}
		ctxConfigsCopy.add(ctxConfig);
		return copyInstance(config, ctxConfigsCopy);
	}

	protected abstract <T extends LoadStepClient> T copyInstance(
		final Config config, final List<Config> ctxConfigs
	);
}
