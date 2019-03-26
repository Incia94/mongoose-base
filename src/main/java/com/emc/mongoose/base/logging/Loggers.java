package com.emc.mongoose.base.logging;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.HashMap;
import java.util.Map;

/** Created by kurila on 05.05.17. */
public interface Loggers {

	String BASE = Loggers.class.getPackage().getName() + '.';
	String BASE_METRICS = BASE + "metrics.";
	String BASE_METRICS_THRESHOLD = BASE_METRICS + "threshold.";
	Logger CLI = LogManager.getLogger(BASE + "Cli");
	Logger CONFIG = LogManager.getLogger(BASE + "Config");
	Logger ERR = LogManager.getLogger(BASE + "Errors");
	Logger OP_TRACES = LogManager.getLogger(BASE + "OpTraces");
	Logger METRICS_FILE = LogManager.getLogger(BASE_METRICS + "File");
	Logger METRICS_FILE_TOTAL = LogManager.getLogger(BASE_METRICS + "FileTotal");
	Logger METRICS_STD_OUT = LogManager.getLogger(BASE_METRICS + "StdOut");
	Logger METRICS_THRESHOLD_FILE_TOTAL = LogManager.getLogger(BASE_METRICS_THRESHOLD + "FileTotal");
	Logger MSG = LogManager.getLogger(BASE + "Messages");
	Logger MULTIPART = LogManager.getLogger(BASE + "Multipart");
	Logger SCENARIO = LogManager.getLogger(BASE + "Scenario");
	Logger TEST = LogManager.getLogger(BASE + "Test");

	Map<String, String> DESCRIPTIONS_BY_NAME = new HashMap<>() {
		{
			put(CLI.getName(), "CLI args");
			put(CONFIG.getName(), "Base config");
			put(ERR.getName(), "Errors");
			put(OP_TRACES.getName(), "Operation Traces");
			put(METRICS_FILE.getName(), "Metrics");
			put(METRICS_FILE_TOTAL.getName(), "Metrics Total");
			put(METRICS_THRESHOLD_FILE_TOTAL.getName(), "Threshold Metrics Total");
			put(MSG.getName(), "Messages");
			put(SCENARIO.getName(), "Scenario");
		}
	};
}
