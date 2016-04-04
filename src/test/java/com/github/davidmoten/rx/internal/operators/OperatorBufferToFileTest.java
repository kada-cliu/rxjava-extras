package com.github.davidmoten.rx.internal.operators;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOError;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.After;
import org.junit.Before;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

import com.github.davidmoten.rx.Actions;
import com.github.davidmoten.rx.Transformers;
import com.github.davidmoten.rx.buffertofile.CacheType;
import com.github.davidmoten.rx.buffertofile.DataSerializer;
import com.github.davidmoten.rx.buffertofile.DataSerializers;
import com.github.davidmoten.rx.buffertofile.Options;
import com.github.davidmoten.rx.slf4j.Logging;

import rx.Observable;
import rx.Scheduler;
import rx.Subscriber;
import rx.functions.Action1;
import rx.observers.TestSubscriber;
import rx.plugins.RxJavaErrorHandler;
import rx.plugins.RxJavaPlugins;
import rx.schedulers.Schedulers;

@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public final class OperatorBufferToFileTest {

	@Before
	@After
	public void resetBefore() {
		RxJavaPlugins ps = RxJavaPlugins.getInstance();

		try {
			Method m = ps.getClass().getDeclaredMethod("reset");
			m.setAccessible(true);
			m.invoke(ps);
		} catch (Throwable ex) {
			ex.printStackTrace();
		}
	}

	@Test
	public void handlesEmpty() {
		TestSubscriber<String> ts = TestSubscriber.create(0);
		Observable.<String> empty()
				.compose(Transformers.onBackpressureBufferToFile(DataSerializers.string(), Schedulers.computation()))
				.subscribe(ts);
		ts.requestMore(1);
		ts.awaitTerminalEvent();
		ts.assertNoErrors();
		ts.assertNoValues();
		ts.assertCompleted();
	}

	@Test
	public void handlesEmptyUsingJavaIOSerialization() {
		TestSubscriber<String> ts = TestSubscriber.create(0);
		Observable.<String> empty().compose(
				Transformers.onBackpressureBufferToFile(DataSerializers.<String> javaIO(), Schedulers.computation()))
				.subscribe(ts);
		ts.requestMore(1);
		ts.awaitTerminalEvent();
		ts.assertNoErrors();
		ts.assertNoValues();
		ts.assertCompleted();
	}

	@Test
	public void handlesThreeUsingJavaIOSerialization() {
		TestSubscriber<String> ts = TestSubscriber.create();
		Observable
				.just("a", "bc", "def").compose(Transformers
						.onBackpressureBufferToFile(DataSerializers.<String> javaIO(), Schedulers.computation()))
				.subscribe(ts);
		ts.awaitTerminalEvent();
		ts.assertNoErrors();
		ts.assertValues("a", "bc", "def");
		ts.assertCompleted();
	}

	@Test
	public void handlesThreeElementsImmediateScheduler() throws InterruptedException {
		checkHandlesThreeElements(createOptions());
	}

	@Test
	public void handlesThreeElementsImmediateSchedulerWeakRef() throws InterruptedException {
		checkHandlesThreeElements(Options.cacheType(CacheType.WEAK_REF).build());
	}

	@Test
	public void handlesThreeElementsImmediateSchedulerSoftRef() throws InterruptedException {
		checkHandlesThreeElements(Options.cacheType(CacheType.SOFT_REF).build());
	}

	@Test
	public void handlesThreeElementsImmediateSchedulerHardRef() throws InterruptedException {
		checkHandlesThreeElements(Options.cacheType(CacheType.HARD_REF).build());
	}

	@Test
	public void handlesThreeElementsImmediateSchedulerLRU() throws InterruptedException {
		checkHandlesThreeElements(Options.cacheType(CacheType.LEAST_RECENTLY_USED).build());
	}

	@Test
	public void handlesThreeElementsImmediateSchedulerWeakWithLimitedCacheAndLimitedStorageSize()
			throws InterruptedException {
		checkHandlesThreeElements(
				Options.cacheType(CacheType.SOFT_REF).cacheSizeItems(1).storageSizeLimitMB(1).build());
	}

	private void checkHandlesThreeElements(Options options) {
		List<String> b = Observable.just("abc", "def", "ghi")
				//
				.compose(Transformers.onBackpressureBufferToFile(DataSerializers.string(), Schedulers.immediate(),
						options))
				.toList().toBlocking().single();
		assertEquals(Arrays.asList("abc", "def", "ghi"), b);
	}

	@Test
	public void handlesThreeElementsImmediateSchedulerSoft() throws InterruptedException {
		List<String> b = Observable.just("abc", "def", "ghi")
				//
				.compose(Transformers.onBackpressureBufferToFile(DataSerializers.string(), Schedulers.immediate(),
						Options.cacheType(CacheType.WEAK_REF).build()))
				.toList().toBlocking().single();
		assertEquals(Arrays.asList("abc", "def", "ghi"), b);
	}

	private static Options createOptions() {
		return Options.cacheType(CacheType.NO_CACHE).build();
	}

	@Test
	public void handlesThreeElementsWithBackpressureAndEnsureCompletionEventArrivesWhenThreeRequested()
			throws InterruptedException {
		TestSubscriber<String> ts = TestSubscriber.create(0);
		Observable.just("abc", "def", "ghi")
				//
				.compose(Transformers.onBackpressureBufferToFile(DataSerializers.string(), Schedulers.computation(),
						createOptions()))
				.subscribe(ts);
		ts.assertNoValues();
		ts.requestMore(2);
		ts.requestMore(1);
		ts.awaitTerminalEvent(10, TimeUnit.SECONDS);
		ts.assertCompleted();
		ts.assertValues("abc", "def", "ghi");
	}

	@Test
	public void handlesErrorSerialization() throws InterruptedException {
		TestSubscriber<String> ts = TestSubscriber.create();
		Observable.<String> error(new IOException("boo"))
				//
				.compose(Transformers.onBackpressureBufferToFile(DataSerializers.string(), Schedulers.computation(),
						createOptions()))
				.subscribe(ts);
		ts.awaitTerminalEvent(10, TimeUnit.SECONDS);
		ts.assertError(IOException.class);
	}

	@Test
	public void handlesErrorWhenDelayErrorIsFalse() throws InterruptedException {
		TestSubscriber<String> ts = TestSubscriber.create(0);
		Observable.just("abc", "def").concatWith(Observable.<String> error(new IOException("boo")))
				//
				.compose(Transformers.onBackpressureBufferToFile(DataSerializers.string(), Schedulers.computation(),
						Options.cacheType(CacheType.NO_CACHE).delayError(false).build()))
				.doOnNext(new Action1<String>() {
					boolean first = true;

					@Override
					public void call(String t) {
						if (first) {
							first = false;
							try {
								TimeUnit.MILLISECONDS.sleep(500);
							} catch (InterruptedException e) {
							}
						}
					}
				}).subscribe(ts);
		ts.requestMore(2);
		ts.awaitTerminalEvent(5000, TimeUnit.SECONDS);
		ts.assertError(IOException.class);
	}

	@Test
	public void handlesUnsubscription() throws InterruptedException {
		TestSubscriber<String> ts = TestSubscriber.create(0);
		Observable.just("abc", "def", "ghi")
				//
				.compose(Transformers.onBackpressureBufferToFile(DataSerializers.string(), Schedulers.computation(),
						createOptions()))
				.subscribe(ts);
		ts.requestMore(2);
		TimeUnit.MILLISECONDS.sleep(500);
		ts.unsubscribe();
		TimeUnit.MILLISECONDS.sleep(500);
		ts.assertValues("abc", "def");
	}

	@Test
	public void handlesUnsubscriptionDuringDrainLoop() throws InterruptedException {
		TestSubscriber<String> ts = TestSubscriber.create(0);
		Observable.just("abc", "def", "ghi")
				//
				.compose(Transformers.onBackpressureBufferToFile(DataSerializers.string(), Schedulers.computation(),
						createOptions()))
				.doOnNext(new Action1<Object>() {

					@Override
					public void call(Object t) {
						try {
							// pauses drain loop
							Thread.sleep(500);
						} catch (InterruptedException e) {
						}
					}
				}).subscribe(ts);
		ts.requestMore(2);
		TimeUnit.MILLISECONDS.sleep(250);
		ts.unsubscribe();
		TimeUnit.MILLISECONDS.sleep(500);
		ts.assertValues("abc");
	}

	@Test
	public void handlesManyLargeMessages() {
		DataSerializer<Integer> serializer = createLargeMessageSerializer();
		int max = 100;
		int last = Observable.range(1, max)
				//
				.compose(Transformers.onBackpressureBufferToFile(serializer, Schedulers.computation(), createOptions()))
				// log
				// .lift(Logging.<Integer> logger().showMemory().log())
				// delay emissions
				.doOnNext(new Action1<Object>() {
					int count = 0;

					@Override
					public void call(Object t) {
						// delay processing of reads for first three items
						count++;
						if (count < 3) {
							try {
								// System.out.println(t);
								Thread.sleep(1000);
							} catch (InterruptedException e) {
								//
							}
						}
					}
				}).last().toBlocking().single();
		assertEquals(max, last);
	}

	@Test
	public void rolloverWorks() throws InterruptedException {
		DataSerializer<Integer> serializer = DataSerializers.integer();
		int max = 100;
		Scheduler scheduler = Schedulers.from(Executors.newFixedThreadPool(1));
		int last = Observable.range(1, max)
				//
				.compose(Transformers.onBackpressureBufferToFile(serializer, scheduler,
						Options.cacheType(CacheType.NO_CACHE).rolloverEvery(10).build()))
				.last().toBlocking().single();
		assertEquals(max, last);
		// wait for all scheduled work to complete (unsubscription)
		waitUntilWorkCompleted(scheduler, 10, TimeUnit.SECONDS);
	}

	private static void waitUntilWorkCompleted(Scheduler scheduler, long duration, TimeUnit unit) {
		final CountDownLatch latch = new CountDownLatch(1);
		scheduler.createWorker().schedule(Actions.countDown(latch));
		try {
			if (!latch.await(duration, unit)) {
				throw new RuntimeException("did not complete");
			}
		} catch (InterruptedException e) {
			throw new RuntimeException(e);
		}
	}

	@Test
	public void handlesTenSecondLoopOfMidStreamUnsubscribe() {
		// run for ten seconds
		long t = System.currentTimeMillis();
		long count = 0;
		while ((System.currentTimeMillis() - t < TimeUnit.SECONDS.toMillis(9))) {
			Scheduler scheduler = Schedulers.from(Executors.newFixedThreadPool(1));
			DataSerializer<Integer> serializer = DataSerializers.integer();
			int max = 1000;
			final CountDownLatch latch = new CountDownLatch(1);
			final AtomicInteger last = new AtomicInteger(-1);
			final AtomicBoolean error = new AtomicBoolean(false);
			final int unsubscribeAfter = max / 2 + 1;
			final List<Integer> list = new ArrayList<Integer>();
			Subscriber<Integer> subscriber = new Subscriber<Integer>() {
				int count = 0;

				@Override
				public void onCompleted() {
					latch.countDown();
				}

				@Override
				public void onError(Throwable e) {
					error.set(true);
				}

				@Override
				public void onNext(Integer t) {
					count++;
					list.add(t);
					if (count == unsubscribeAfter) {
						unsubscribe();
						last.set(count);
						latch.countDown();
					}
				}
			};
			Observable.range(1, max)
					//
					.compose(Transformers.onBackpressureBufferToFile(serializer, scheduler,
							Options.cacheType(CacheType.NO_CACHE).rolloverEvery(max / 10).build()))
					.subscribe(subscriber);
			assertFalse(error.get());
			List<Integer> expected = new ArrayList<Integer>();
			for (int i = 1; i <= unsubscribeAfter; i++) {
				expected.add(i);
			}
			assertEquals(expected, list);
			waitUntilWorkCompleted(scheduler, 100, TimeUnit.SECONDS);
			count++;
		}
		System.out.println(count + " cycles passed");
	}

	@Test
	public void checkRateForSmallMessages() {
		Scheduler scheduler = Schedulers.from(Executors.newFixedThreadPool(1));
		DataSerializer<Integer> serializer = DataSerializers.integer();
		int max = 3000;
		long t = System.currentTimeMillis();
		int last = Observable.range(1, max)
				//
				.compose(Transformers.onBackpressureBufferToFile(serializer, scheduler,
						Options.cacheType(CacheType.NO_CACHE).build()))
				// log
				// .lift(Logging.<Integer>
				// logger().showCount().every(1000).showMemory().log())
				.last().toBlocking().single();
		t = System.currentTimeMillis() - t;
		assertEquals(max, last);
		System.out.println("rate = " + (double) max / (t) * 1000);
		waitUntilWorkCompleted(scheduler, 100, TimeUnit.SECONDS);
		// about 33,000 messages per second on i7 for NO_CACHE
		// about 46,000 messages per second on i7 for WEAK_REF
	}

	@Test
	public void testForReadMe() {
		DataSerializer<String> serializer = new DataSerializer<String>() {

			@Override
			public void serialize(DataOutput output, String s) throws IOException {
				output.writeUTF(s);
			}

			@Override
			public String deserialize(DataInput input, int availableBytes) throws IOException {
				return input.readUTF();
			}
		};
		List<String> list = Observable.just("a", "b", "c")
				.compose(Transformers.onBackpressureBufferToFile(serializer, Schedulers.computation())).toList()
				.toBlocking().single();
		assertEquals(Arrays.asList("a", "b", "c"), list);
	}

	@Test
	public void testOverflow() throws InterruptedException {
		Scheduler scheduler = Schedulers.from(Executors.newFixedThreadPool(1));
		final AtomicReference<Throwable> error = new AtomicReference<Throwable>();
		RxJavaErrorHandler handler = new RxJavaErrorHandler() {

			public void handleError(Throwable t) {
				error.set(t);
			}
		};
		RxJavaPlugins.getInstance().registerErrorHandler(handler);
		TestSubscriber<Integer> ts = TestSubscriber.create();
		DataSerializer<Integer> serializer = createLargeMessageSerializer();
		int max = 100;
		Observable.range(1, max)
				//
				.compose(Transformers.onBackpressureBufferToFile(serializer, scheduler,
						Options.cacheType(CacheType.NO_CACHE).storageSizeLimitMB(1).build()))
				.delay(50, TimeUnit.MILLISECONDS, Schedulers.immediate()).last().subscribe(ts);
		ts.awaitTerminalEvent();
		ts.assertError(IOError.class);
		// wait for unsubscribe
		waitUntilWorkCompleted(scheduler, 5, TimeUnit.SECONDS);
		assertTrue(error.get() != null && error.get() instanceof IllegalStateException);
	}

	private DataSerializer<Integer> createLargeMessageSerializer() {
		DataSerializer<Integer> serializer = new DataSerializer<Integer>() {

			final static int dummyArraySize = 1000000;// 1MB
			final static int chunkSize = 1000;

			@Override
			public void serialize(DataOutput output, Integer n) throws IOException {
				output.writeInt(n);
				// write some filler
				int toWrite = dummyArraySize;
				while (toWrite > 0) {
					if (toWrite >= chunkSize) {
						output.write(new byte[chunkSize]);
						toWrite -= chunkSize;
					} else {
						output.write(new byte[toWrite]);
						toWrite = 0;
					}
				}
				// System.out.println("written " + n);
			}

			@Override
			public Integer deserialize(DataInput input, int availableBytes) throws IOException {
				int value = input.readInt();
				// read the filler
				int bytesRead = 0;
				while (bytesRead < dummyArraySize) {
					if (dummyArraySize - bytesRead >= chunkSize) {
						input.readFully(new byte[chunkSize]);
						bytesRead += chunkSize;
					} else {
						input.readFully(new byte[dummyArraySize - bytesRead]);
						bytesRead = dummyArraySize;
					}
				}
				// System.out.println("read " + value);
				return value;
			}
		};
		return serializer;
	}

	@Test
	public void serializesListsUsingJavaIO() {
		List<Integer> list = Observable.just(1, 2, 3, 4).buffer(2)
				.compose(Transformers.<List<Integer>> onBackpressureBufferToFile()).last().toBlocking().single();
		assertEquals(Arrays.asList(3, 4), list);
	}

	public static void main(String[] args) throws InterruptedException {
		Observable.range(1, Integer.MAX_VALUE)
				//
				.compose(Transformers.onBackpressureBufferToFile(DataSerializers.integer(), Schedulers.computation()))
				//
				.lift(Logging.<Integer> logger().showCount().every(10000).showMemory().log())
				//
				// .delay(200, TimeUnit.MILLISECONDS, Schedulers.immediate())
				//
				.count().toBlocking().single();
		Thread.sleep(1000);
	}

}
