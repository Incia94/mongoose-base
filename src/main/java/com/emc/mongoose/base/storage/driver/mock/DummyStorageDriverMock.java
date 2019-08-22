package com.emc.mongoose.base.storage.driver.mock;

import static com.github.akurilov.commons.lang.Exceptions.throwUnchecked;

import com.emc.mongoose.base.item.DataItem;
import com.emc.mongoose.base.item.Item;
import com.emc.mongoose.base.item.ItemFactory;
import com.emc.mongoose.base.item.op.OpType;
import com.emc.mongoose.base.item.op.Operation;
import com.emc.mongoose.base.item.op.data.DataOperation;
import com.emc.mongoose.base.logging.Loggers;
import com.emc.mongoose.base.storage.driver.StorageDriver;
import com.github.akurilov.commons.collection.Range;
import com.github.akurilov.commons.concurrent.AsyncRunnableBase;
import com.github.akurilov.commons.io.Input;
import com.github.akurilov.commons.io.Output;
import com.github.akurilov.confuse.Config;
import java.io.EOFException;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

/** Created by andrey on 11.05.17. */
public final class DummyStorageDriverMock<I extends Item, O extends Operation<I>>
				extends AsyncRunnableBase implements StorageDriver<I, O> {

	private final int concurrencyLimit;
	private final LongAdder scheduledOpCount = new LongAdder();
	private final LongAdder completedOpCount = new LongAdder();
	private Output<O> opResultOut = null;

	public DummyStorageDriverMock(final Config storageConfig) {
		final Config limitConfig = storageConfig.configVal("driver-limit");
		this.concurrencyLimit = limitConfig.intVal("concurrency");
	}

	@Override
	public final boolean put(final O task) {
		if (!isStarted()) {
			throwUnchecked(new EOFException());
		}
		checkStateFor(task);
		if (opResultOut.put(task)) {
			scheduledOpCount.increment();
			completedOpCount.increment();
			return true;
		} else {
			return false;
		}
	}

	@Override
	public final int put(final List<O> tasks, final int from, final int to) {
		if (!isStarted()) {
			throwUnchecked(new EOFException());
		}
		int i = from;
		O nextTask;
		while (i < to && isStarted()) {
			nextTask = tasks.get(i);
			checkStateFor(nextTask);
			if (opResultOut.put(tasks.get(i))) {
				i++;
			} else {
				break;
			}
		}
		final int n = i - from;
		scheduledOpCount.add(n);
		completedOpCount.add(n);
		return n;
	}

	@Override
	public final int put(final List<O> tasks) {
		if (!isStarted()) {
			throwUnchecked(new EOFException());
		}
		int n = 0;
		for (final O nextOp : tasks) {
			if (isStarted()) {
				checkStateFor(nextOp);
				if (opResultOut.put(nextOp)) {
					n++;
				} else {
					break;
				}
			} else {
				break;
			}
		}
		scheduledOpCount.add(n);
		completedOpCount.add(n);
		return n;
	}

	@Override
	public final Input<O> getInput() {
		throw new AssertionError();
	}

	private void checkStateFor(final O op) {
		op.reset();
		op.startRequest();
		op.finishRequest();
		op.startResponse();
		if (op instanceof DataOperation) {
			final DataOperation dataOp = (DataOperation) op;
			final DataItem dataItem = dataOp.item();
			switch (dataOp.type()) {
			case CREATE:
				try {
					dataOp.countBytesDone(dataItem.size());
				} catch (final IOException ignored) {}
				break;
			case READ:
				dataOp.startDataResponse();
			case UPDATE:
				final List<Range> fixedRanges = dataOp.fixedRanges();
				if (fixedRanges == null || fixedRanges.isEmpty()) {
					if (dataOp.hasMarkedRanges()) {
						dataOp.countBytesDone(dataOp.markedRangesSize());
					} else {
						try {
							dataOp.countBytesDone(dataItem.size());
						} catch (final IOException ignored) {}
					}
				} else {
					dataOp.countBytesDone(dataOp.markedRangesSize());
				}
				break;
			default:
				break;
			}
			dataOp.startDataResponse();
		}
		op.finishResponse();
		op.status(Operation.Status.SUCC);
	}

	@Override
	public final void operationResultOutput(final Output<O> opResultOut) {
		this.opResultOut = opResultOut;
	}

	@Override
	public final List<I> list(
					final ItemFactory<I> itemFactory,
					final String path,
					final String prefix,
					final int idRadix,
					final I lastPrevItem,
					final int count)
					throws IOException {
		return Collections.emptyList();
	}

	@Override
	public final int concurrencyLimit() {
		return concurrencyLimit;
	}

	@Override
	public final int activeOpCount() {
		return (int) (scheduledOpCount() - completedOpCount());
	}

	@Override
	public final long scheduledOpCount() {
		return scheduledOpCount.sum();
	}

	@Override
	public final long completedOpCount() {
		return completedOpCount.sum();
	}

	@Override
	public final boolean isIdle() {
		return true;
	}

	@Override
	public final void adjustIoBuffers(final long avgTransferSize, final OpType opType) {}

	@Override
	protected void doStart() throws IllegalStateException {
		Loggers.MSG.debug("{}: started", toString());
	}

	@Override
	protected final void doShutdown() throws IllegalStateException {
		Loggers.MSG.debug("{}: shut down", toString());
	}

	@Override
	public final boolean await(final long timeout, final TimeUnit timeUnit)
					throws InterruptedException {
		return true;
	}

	@Override
	protected final void doStop() throws IllegalStateException {
		Loggers.MSG.debug("{}: stopped", toString());
	}

	@Override
	protected final void doClose() throws IOException {
		opResultOut = null;
		Loggers.MSG.debug("{}: closed", toString());
	}

	@Override
	public final String toString() {
		return String.format(super.toString(), "mock-dummy");
	}
}
