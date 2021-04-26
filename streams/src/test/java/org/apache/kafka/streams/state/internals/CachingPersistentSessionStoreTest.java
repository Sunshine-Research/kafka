/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.kafka.streams.state.internals;

import org.apache.kafka.common.metrics.Metrics;
import org.apache.kafka.common.serialization.Deserializer;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.utils.Bytes;
import org.apache.kafka.common.utils.LogContext;
import org.apache.kafka.streams.KeyValue;
import org.apache.kafka.streams.KeyValueTimestamp;
import org.apache.kafka.streams.errors.InvalidStateStoreException;
import org.apache.kafka.streams.kstream.SessionWindowedDeserializer;
import org.apache.kafka.streams.kstream.Windowed;
import org.apache.kafka.streams.kstream.internals.Change;
import org.apache.kafka.streams.kstream.internals.SessionWindow;
import org.apache.kafka.streams.processor.StateStoreContext;
import org.apache.kafka.streams.processor.internals.MockStreamsMetrics;
import org.apache.kafka.streams.processor.internals.ProcessorRecordContext;
import org.apache.kafka.streams.processor.internals.testutil.LogCaptureAppender;
import org.apache.kafka.streams.state.KeyValueIterator;
import org.apache.kafka.streams.state.SessionStore;
import org.apache.kafka.test.InternalMockProcessorContext;
import org.apache.kafka.test.TestUtils;
import org.easymock.EasyMock;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Random;

import static java.util.Arrays.asList;
import static org.apache.kafka.test.StreamsTestUtils.toList;
import static org.apache.kafka.test.StreamsTestUtils.verifyKeyValueList;
import static org.apache.kafka.test.StreamsTestUtils.verifyWindowedKeyValue;
import static org.hamcrest.CoreMatchers.hasItem;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class CachingPersistentSessionStoreTest {

	private static final int MAX_CACHE_SIZE_BYTES = 600;
	private static final Long DEFAULT_TIMESTAMP = 10L;
	private static final long SEGMENT_INTERVAL = 100L;
	private static final String TOPIC = "topic";
	private static final String CACHE_NAMESPACE = "0_0-store-name";

	private final Bytes keyA = Bytes.wrap("a".getBytes());
	private final Bytes keyAA = Bytes.wrap("aa".getBytes());
	private final Bytes keyB = Bytes.wrap("b".getBytes());

	private SessionStore<Bytes, byte[]> underlyingStore;
	private CachingSessionStore cachingStore;
	private ThreadCache cache;

	@Before
	public void before() {
		final RocksDBSegmentedBytesStore segmented = new RocksDBSegmentedBytesStore(
				"store-name",
				"metric-scope",
				Long.MAX_VALUE,
				SEGMENT_INTERVAL,
				new SessionKeySchema()
		);
		underlyingStore = new RocksDBSessionStore(segmented);
		cachingStore = new CachingSessionStore(underlyingStore, SEGMENT_INTERVAL);
		cache = new ThreadCache(new LogContext("testCache "), MAX_CACHE_SIZE_BYTES, new MockStreamsMetrics(new Metrics()));
		final InternalMockProcessorContext context =
				new InternalMockProcessorContext(TestUtils.tempDirectory(), null, null, null, cache);
		context.setRecordContext(new ProcessorRecordContext(DEFAULT_TIMESTAMP, 0, 0, TOPIC, null));
		cachingStore.init((StateStoreContext) context, cachingStore);
	}

	@After
	public void after() {
		cachingStore.close();
	}

	@Test
	public void shouldPutFetchFromCache() {
		cachingStore.put(new Windowed<>(keyA, new SessionWindow(0, 0)), "1".getBytes());
		cachingStore.put(new Windowed<>(keyAA, new SessionWindow(0, 0)), "1".getBytes());
		cachingStore.put(new Windowed<>(keyB, new SessionWindow(0, 0)), "1".getBytes());

		assertEquals(3, cache.size());

		final KeyValueIterator<Windowed<Bytes>, byte[]> a =
				cachingStore.findSessions(keyA, 0, 0);
		final KeyValueIterator<Windowed<Bytes>, byte[]> b =
				cachingStore.findSessions(keyB, 0, 0);

		verifyWindowedKeyValue(a.next(), new Windowed<>(keyA, new SessionWindow(0, 0)), "1");
		verifyWindowedKeyValue(b.next(), new Windowed<>(keyB, new SessionWindow(0, 0)), "1");
		assertFalse(a.hasNext());
		assertFalse(b.hasNext());
	}

	@Test
	public void shouldPutFetchAllKeysFromCache() {
		cachingStore.put(new Windowed<>(keyA, new SessionWindow(0, 0)), "1".getBytes());
		cachingStore.put(new Windowed<>(keyAA, new SessionWindow(0, 0)), "1".getBytes());
		cachingStore.put(new Windowed<>(keyB, new SessionWindow(0, 0)), "1".getBytes());

		assertEquals(3, cache.size());

		final KeyValueIterator<Windowed<Bytes>, byte[]> all =
				cachingStore.findSessions(keyA, keyB, 0, 0);
		verifyWindowedKeyValue(all.next(), new Windowed<>(keyA, new SessionWindow(0, 0)), "1");
		verifyWindowedKeyValue(all.next(), new Windowed<>(keyAA, new SessionWindow(0, 0)), "1");
		verifyWindowedKeyValue(all.next(), new Windowed<>(keyB, new SessionWindow(0, 0)), "1");
		assertFalse(all.hasNext());
	}

	@Test
	public void shouldPutBackwardFetchAllKeysFromCache() {
		cachingStore.put(new Windowed<>(keyA, new SessionWindow(0, 0)), "1".getBytes());
		cachingStore.put(new Windowed<>(keyAA, new SessionWindow(0, 0)), "1".getBytes());
		cachingStore.put(new Windowed<>(keyB, new SessionWindow(0, 0)), "1".getBytes());

		assertEquals(3, cache.size());

		final KeyValueIterator<Windowed<Bytes>, byte[]> all =
				cachingStore.backwardFindSessions(keyA, keyB, 0, 0);
		verifyWindowedKeyValue(all.next(), new Windowed<>(keyB, new SessionWindow(0, 0)), "1");
		verifyWindowedKeyValue(all.next(), new Windowed<>(keyAA, new SessionWindow(0, 0)), "1");
		verifyWindowedKeyValue(all.next(), new Windowed<>(keyA, new SessionWindow(0, 0)), "1");
		assertFalse(all.hasNext());
	}

	@Test
	public void shouldCloseWrappedStoreAndCacheAfterErrorDuringCacheFlush() {
		setUpCloseTests();
		EasyMock.reset(cache);
		cache.flush(CACHE_NAMESPACE);
		EasyMock.expectLastCall().andThrow(new RuntimeException("Simulating an error on flush"));
		EasyMock.replay(cache);
		EasyMock.reset(underlyingStore);
		underlyingStore.close();
		EasyMock.replay(underlyingStore);

		assertThrows(RuntimeException.class, cachingStore::close);
		EasyMock.verify(cache, underlyingStore);
	}

	@Test
	public void shouldCloseWrappedStoreAfterErrorDuringCacheClose() {
		setUpCloseTests();
		EasyMock.reset(cache);
		cache.flush(CACHE_NAMESPACE);
		cache.close(CACHE_NAMESPACE);
		EasyMock.expectLastCall().andThrow(new RuntimeException("Simulating an error on close"));
		EasyMock.replay(cache);
		EasyMock.reset(underlyingStore);
		underlyingStore.close();
		EasyMock.replay(underlyingStore);

		assertThrows(RuntimeException.class, cachingStore::close);
		EasyMock.verify(cache, underlyingStore);
	}

	@Test
	public void shouldCloseCacheAfterErrorDuringWrappedStoreClose() {
		setUpCloseTests();
		EasyMock.reset(cache);
		cache.flush(CACHE_NAMESPACE);
		cache.close(CACHE_NAMESPACE);
		EasyMock.replay(cache);
		EasyMock.reset(underlyingStore);
		underlyingStore.close();
		EasyMock.expectLastCall().andThrow(new RuntimeException("Simulating an error on close"));
		EasyMock.replay(underlyingStore);

		assertThrows(RuntimeException.class, cachingStore::close);
		EasyMock.verify(cache, underlyingStore);
	}

	private void setUpCloseTests() {
		underlyingStore = EasyMock.createNiceMock(SessionStore.class);
		EasyMock.expect(underlyingStore.name()).andStubReturn("store-name");
		EasyMock.expect(underlyingStore.isOpen()).andStubReturn(true);
		EasyMock.replay(underlyingStore);
		cachingStore = new CachingSessionStore(underlyingStore, SEGMENT_INTERVAL);
		cache = EasyMock.niceMock(ThreadCache.class);
		final InternalMockProcessorContext context =
				new InternalMockProcessorContext(TestUtils.tempDirectory(), null, null, null, cache);
		context.setRecordContext(new ProcessorRecordContext(10, 0, 0, TOPIC, null));
		cachingStore.init((StateStoreContext) context, cachingStore);
	}

	@Test
	public void shouldPutFetchRangeFromCache() {
		cachingStore.put(new Windowed<>(keyA, new SessionWindow(0, 0)), "1".getBytes());
		cachingStore.put(new Windowed<>(keyAA, new SessionWindow(0, 0)), "1".getBytes());
		cachingStore.put(new Windowed<>(keyB, new SessionWindow(0, 0)), "1".getBytes());

		assertEquals(3, cache.size());

		final KeyValueIterator<Windowed<Bytes>, byte[]> some =
				cachingStore.findSessions(keyAA, keyB, 0, 0);
		verifyWindowedKeyValue(some.next(), new Windowed<>(keyAA, new SessionWindow(0, 0)), "1");
		verifyWindowedKeyValue(some.next(), new Windowed<>(keyB, new SessionWindow(0, 0)), "1");
		assertFalse(some.hasNext());
	}

	@Test
	public void shouldPutBackwardFetchRangeFromCache() {
		cachingStore.put(new Windowed<>(keyA, new SessionWindow(0, 0)), "1".getBytes());
		cachingStore.put(new Windowed<>(keyAA, new SessionWindow(0, 0)), "1".getBytes());
		cachingStore.put(new Windowed<>(keyB, new SessionWindow(0, 0)), "1".getBytes());

		assertEquals(3, cache.size());

		final KeyValueIterator<Windowed<Bytes>, byte[]> some =
				cachingStore.backwardFindSessions(keyAA, keyB, 0, 0);
		verifyWindowedKeyValue(some.next(), new Windowed<>(keyB, new SessionWindow(0, 0)), "1");
		verifyWindowedKeyValue(some.next(), new Windowed<>(keyAA, new SessionWindow(0, 0)), "1");
		assertFalse(some.hasNext());
	}

	@Test
	public void shouldFetchAllSessionsWithSameRecordKey() {
		final List<KeyValue<Windowed<Bytes>, byte[]>> expected = asList(
				KeyValue.pair(new Windowed<>(keyA, new SessionWindow(0, 0)), "1".getBytes()),
				KeyValue.pair(new Windowed<>(keyA, new SessionWindow(10, 10)), "2".getBytes()),
				KeyValue.pair(new Windowed<>(keyA, new SessionWindow(100, 100)), "3".getBytes()),
				KeyValue.pair(new Windowed<>(keyA, new SessionWindow(1000, 1000)), "4".getBytes())
		);
		for (final KeyValue<Windowed<Bytes>, byte[]> kv : expected) {
			cachingStore.put(kv.key, kv.value);
		}

		// add one that shouldn't appear in the results
		cachingStore.put(new Windowed<>(keyAA, new SessionWindow(0, 0)), "5".getBytes());

		final List<KeyValue<Windowed<Bytes>, byte[]>> results = toList(cachingStore.fetch(keyA));
		verifyKeyValueList(expected, results);
	}

	@Test
	public void shouldBackwardFetchAllSessionsWithSameRecordKey() {
		final List<KeyValue<Windowed<Bytes>, byte[]>> expected = asList(
				KeyValue.pair(new Windowed<>(keyA, new SessionWindow(0, 0)), "1".getBytes()),
				KeyValue.pair(new Windowed<>(keyA, new SessionWindow(10, 10)), "2".getBytes()),
				KeyValue.pair(new Windowed<>(keyA, new SessionWindow(100, 100)), "3".getBytes()),
				KeyValue.pair(new Windowed<>(keyA, new SessionWindow(1000, 1000)), "4".getBytes())
		);
		for (final KeyValue<Windowed<Bytes>, byte[]> kv : expected) {
			cachingStore.put(kv.key, kv.value);
		}

		// add one that shouldn't appear in the results
		cachingStore.put(new Windowed<>(keyAA, new SessionWindow(0, 0)), "5".getBytes());

		final List<KeyValue<Windowed<Bytes>, byte[]>> results = toList(cachingStore.backwardFetch(keyA));
		Collections.reverse(results);
		verifyKeyValueList(expected, results);
	}

	@Test
	public void shouldFlushItemsToStoreOnEviction() {
		final List<KeyValue<Windowed<Bytes>, byte[]>> added = addSessionsUntilOverflow("a", "b", "c", "d");
		assertEquals(added.size() - 1, cache.size());
		final KeyValueIterator<Windowed<Bytes>, byte[]> iterator =
				cachingStore.findSessions(added.get(0).key.key(), 0, 0);
		final KeyValue<Windowed<Bytes>, byte[]> next = iterator.next();
		assertEquals(added.get(0).key, next.key);
		assertArrayEquals(added.get(0).value, next.value);
	}

	@Test
	public void shouldQueryItemsInCacheAndStore() {
		final List<KeyValue<Windowed<Bytes>, byte[]>> added = addSessionsUntilOverflow("a");
		final KeyValueIterator<Windowed<Bytes>, byte[]> iterator = cachingStore.findSessions(
				Bytes.wrap("a".getBytes(StandardCharsets.UTF_8)),
				0,
				added.size() * 10L
		);
		final List<KeyValue<Windowed<Bytes>, byte[]>> actual = toList(iterator);
		verifyKeyValueList(added, actual);
	}

	@Test
	public void shouldRemove() {
		final Windowed<Bytes> a = new Windowed<>(keyA, new SessionWindow(0, 0));
		final Windowed<Bytes> b = new Windowed<>(keyB, new SessionWindow(0, 0));
		cachingStore.put(a, "2".getBytes());
		cachingStore.put(b, "2".getBytes());
		cachingStore.remove(a);

		final KeyValueIterator<Windowed<Bytes>, byte[]> rangeIter =
				cachingStore.findSessions(keyA, 0, 0);
		assertFalse(rangeIter.hasNext());

		assertNull(cachingStore.fetchSession(keyA, 0, 0));
		assertThat(cachingStore.fetchSession(keyB, 0, 0), equalTo("2".getBytes()));

	}

	@Test
	public void shouldFetchCorrectlyAcrossSegments() {
		final Windowed<Bytes> a1 = new Windowed<>(keyA, new SessionWindow(SEGMENT_INTERVAL * 0, SEGMENT_INTERVAL * 0));
		final Windowed<Bytes> a2 = new Windowed<>(keyA, new SessionWindow(SEGMENT_INTERVAL * 1, SEGMENT_INTERVAL * 1));
		final Windowed<Bytes> a3 = new Windowed<>(keyA, new SessionWindow(SEGMENT_INTERVAL * 2, SEGMENT_INTERVAL * 2));
		final Windowed<Bytes> a4 = new Windowed<>(keyA, new SessionWindow(SEGMENT_INTERVAL * 3, SEGMENT_INTERVAL * 3));
		final Windowed<Bytes> a5 = new Windowed<>(keyA, new SessionWindow(SEGMENT_INTERVAL * 4, SEGMENT_INTERVAL * 4));
		final Windowed<Bytes> a6 = new Windowed<>(keyA, new SessionWindow(SEGMENT_INTERVAL * 5, SEGMENT_INTERVAL * 5));
		cachingStore.put(a1, "1".getBytes());
		cachingStore.put(a2, "2".getBytes());
		cachingStore.put(a3, "3".getBytes());
		cachingStore.flush();
		cachingStore.put(a4, "4".getBytes());
		cachingStore.put(a5, "5".getBytes());
		cachingStore.put(a6, "6".getBytes());
		final KeyValueIterator<Windowed<Bytes>, byte[]> results =
				cachingStore.findSessions(keyA, 0, SEGMENT_INTERVAL * 5);
		assertEquals(a1, results.next().key);
		assertEquals(a2, results.next().key);
		assertEquals(a3, results.next().key);
		assertEquals(a4, results.next().key);
		assertEquals(a5, results.next().key);
		assertEquals(a6, results.next().key);
		assertFalse(results.hasNext());
	}

	@Test
	public void shouldBackwardFetchCorrectlyAcrossSegments() {
		final Windowed<Bytes> a1 = new Windowed<>(keyA, new SessionWindow(SEGMENT_INTERVAL * 0, SEGMENT_INTERVAL * 0));
		final Windowed<Bytes> a2 = new Windowed<>(keyA, new SessionWindow(SEGMENT_INTERVAL * 1, SEGMENT_INTERVAL * 1));
		final Windowed<Bytes> a3 = new Windowed<>(keyA, new SessionWindow(SEGMENT_INTERVAL * 2, SEGMENT_INTERVAL * 2));
		final Windowed<Bytes> a4 = new Windowed<>(keyA, new SessionWindow(SEGMENT_INTERVAL * 3, SEGMENT_INTERVAL * 3));
		final Windowed<Bytes> a5 = new Windowed<>(keyA, new SessionWindow(SEGMENT_INTERVAL * 4, SEGMENT_INTERVAL * 4));
		final Windowed<Bytes> a6 = new Windowed<>(keyA, new SessionWindow(SEGMENT_INTERVAL * 5, SEGMENT_INTERVAL * 5));
		cachingStore.put(a1, "1".getBytes());
		cachingStore.put(a2, "2".getBytes());
		cachingStore.put(a3, "3".getBytes());
		cachingStore.flush();
		cachingStore.put(a4, "4".getBytes());
		cachingStore.put(a5, "5".getBytes());
		cachingStore.put(a6, "6".getBytes());
		final KeyValueIterator<Windowed<Bytes>, byte[]> results =
				cachingStore.backwardFindSessions(keyA, 0, SEGMENT_INTERVAL * 5);
		assertEquals(a6, results.next().key);
		assertEquals(a5, results.next().key);
		assertEquals(a4, results.next().key);
		assertEquals(a3, results.next().key);
		assertEquals(a2, results.next().key);
		assertEquals(a1, results.next().key);
		assertFalse(results.hasNext());
	}

	@Test
	public void shouldFetchRangeCorrectlyAcrossSegments() {
		final Windowed<Bytes> a1 = new Windowed<>(keyA, new SessionWindow(SEGMENT_INTERVAL * 0, SEGMENT_INTERVAL * 0));
		final Windowed<Bytes> aa1 = new Windowed<>(keyAA, new SessionWindow(SEGMENT_INTERVAL * 0, SEGMENT_INTERVAL * 0));
		final Windowed<Bytes> a2 = new Windowed<>(keyA, new SessionWindow(SEGMENT_INTERVAL * 1, SEGMENT_INTERVAL * 1));
		final Windowed<Bytes> a3 = new Windowed<>(keyA, new SessionWindow(SEGMENT_INTERVAL * 2, SEGMENT_INTERVAL * 2));
		final Windowed<Bytes> aa3 = new Windowed<>(keyAA, new SessionWindow(SEGMENT_INTERVAL * 2, SEGMENT_INTERVAL * 2));
		cachingStore.put(a1, "1".getBytes());
		cachingStore.put(aa1, "1".getBytes());
		cachingStore.put(a2, "2".getBytes());
		cachingStore.put(a3, "3".getBytes());
		cachingStore.put(aa3, "3".getBytes());

		final KeyValueIterator<Windowed<Bytes>, byte[]> rangeResults =
				cachingStore.findSessions(keyA, keyAA, 0, SEGMENT_INTERVAL * 2);
		final List<Windowed<Bytes>> keys = new ArrayList<>();
		while (rangeResults.hasNext()) {
			keys.add(rangeResults.next().key);
		}
		rangeResults.close();
		assertEquals(asList(a1, aa1, a2, a3, aa3), keys);
	}

	@Test
	public void shouldBackwardFetchRangeCorrectlyAcrossSegments() {
		final Windowed<Bytes> a1 = new Windowed<>(keyA, new SessionWindow(SEGMENT_INTERVAL * 0, SEGMENT_INTERVAL * 0));
		final Windowed<Bytes> aa1 = new Windowed<>(keyAA, new SessionWindow(SEGMENT_INTERVAL * 0, SEGMENT_INTERVAL * 0));
		final Windowed<Bytes> a2 = new Windowed<>(keyA, new SessionWindow(SEGMENT_INTERVAL * 1, SEGMENT_INTERVAL * 1));
		final Windowed<Bytes> a3 = new Windowed<>(keyA, new SessionWindow(SEGMENT_INTERVAL * 2, SEGMENT_INTERVAL * 2));
		final Windowed<Bytes> aa3 = new Windowed<>(keyAA, new SessionWindow(SEGMENT_INTERVAL * 2, SEGMENT_INTERVAL * 2));
		cachingStore.put(a1, "1".getBytes());
		cachingStore.put(aa1, "1".getBytes());
		cachingStore.put(a2, "2".getBytes());
		cachingStore.put(a3, "3".getBytes());
		cachingStore.put(aa3, "3".getBytes());

		final KeyValueIterator<Windowed<Bytes>, byte[]> rangeResults =
				cachingStore.backwardFindSessions(keyA, keyAA, 0, SEGMENT_INTERVAL * 2);
		final List<Windowed<Bytes>> keys = new ArrayList<>();
		while (rangeResults.hasNext()) {
			keys.add(rangeResults.next().key);
		}
		rangeResults.close();
		assertEquals(asList(aa3, a3, a2, aa1, a1), keys);
	}

	@Test
	public void shouldSetFlushListener() {
		assertTrue(cachingStore.setFlushListener(null, true));
		assertTrue(cachingStore.setFlushListener(null, false));
	}

	@Test
	public void shouldForwardChangedValuesDuringFlush() {
		final Windowed<Bytes> a = new Windowed<>(keyA, new SessionWindow(2, 4));
		final Windowed<Bytes> b = new Windowed<>(keyA, new SessionWindow(1, 2));
		final Windowed<String> aDeserialized = new Windowed<>("a", new SessionWindow(2, 4));
		final Windowed<String> bDeserialized = new Windowed<>("a", new SessionWindow(1, 2));
		final CacheFlushListenerStub<Windowed<String>, String> flushListener =
				new CacheFlushListenerStub<>(
						new SessionWindowedDeserializer<>(new StringDeserializer()),
						new StringDeserializer()
				);
		cachingStore.setFlushListener(flushListener, true);

		cachingStore.put(b, "1".getBytes());
		cachingStore.flush();

		assertEquals(
				Collections.singletonList(
						new KeyValueTimestamp<>(
								bDeserialized,
								new Change<>("1", null),
								DEFAULT_TIMESTAMP
						)
				),
				flushListener.forwarded
		);
		flushListener.forwarded.clear();

		cachingStore.put(a, "1".getBytes());
		cachingStore.flush();

		assertEquals(
				Collections.singletonList(
						new KeyValueTimestamp<>(
								aDeserialized,
								new Change<>("1", null),
								DEFAULT_TIMESTAMP
						)
				),
				flushListener.forwarded
		);
		flushListener.forwarded.clear();

		cachingStore.put(a, "2".getBytes());
		cachingStore.flush();

		assertEquals(
				Collections.singletonList(
						new KeyValueTimestamp<>(
								aDeserialized,
								new Change<>("2", "1"),
								DEFAULT_TIMESTAMP
						)
				),
				flushListener.forwarded
		);
		flushListener.forwarded.clear();

		cachingStore.remove(a);
		cachingStore.flush();

		assertEquals(
				Collections.singletonList(
						new KeyValueTimestamp<>(
								aDeserialized,
								new Change<>(null, "2"),
								DEFAULT_TIMESTAMP
						)
				),
				flushListener.forwarded
		);
		flushListener.forwarded.clear();

		cachingStore.put(a, "1".getBytes());
		cachingStore.put(a, "2".getBytes());
		cachingStore.remove(a);
		cachingStore.flush();

		assertEquals(
				Collections.emptyList(),
				flushListener.forwarded
		);
		flushListener.forwarded.clear();
	}

	@Test
	public void shouldNotForwardChangedValuesDuringFlushWhenSendOldValuesDisabled() {
		final Windowed<Bytes> a = new Windowed<>(keyA, new SessionWindow(0, 0));
		final Windowed<String> aDeserialized = new Windowed<>("a", new SessionWindow(0, 0));
		final CacheFlushListenerStub<Windowed<String>, String> flushListener =
				new CacheFlushListenerStub<>(
						new SessionWindowedDeserializer<>(new StringDeserializer()),
						new StringDeserializer());
		cachingStore.setFlushListener(flushListener, false);

		cachingStore.put(a, "1".getBytes());
		cachingStore.flush();

		cachingStore.put(a, "2".getBytes());
		cachingStore.flush();

		cachingStore.remove(a);
		cachingStore.flush();

		assertEquals(
				asList(
						new KeyValueTimestamp<>(
								aDeserialized,
								new Change<>("1", null),
								DEFAULT_TIMESTAMP
						),
						new KeyValueTimestamp<>(
								aDeserialized,
								new Change<>("2", null),
								DEFAULT_TIMESTAMP
						),
						new KeyValueTimestamp<>(
								aDeserialized,
								new Change<>(null, null),
								DEFAULT_TIMESTAMP
						)
				),
				flushListener.forwarded
		);
		flushListener.forwarded.clear();

		cachingStore.put(a, "1".getBytes());
		cachingStore.put(a, "2".getBytes());
		cachingStore.remove(a);
		cachingStore.flush();

		assertEquals(
				Collections.emptyList(),
				flushListener.forwarded
		);
		flushListener.forwarded.clear();
	}

	@Test
	public void shouldReturnSameResultsForSingleKeyFindSessionsAndEqualKeyRangeFindSessions() {
		cachingStore.put(new Windowed<>(keyA, new SessionWindow(0, 1)), "1".getBytes());
		cachingStore.put(new Windowed<>(keyAA, new SessionWindow(2, 3)), "2".getBytes());
		cachingStore.put(new Windowed<>(keyAA, new SessionWindow(4, 5)), "3".getBytes());
		cachingStore.put(new Windowed<>(keyB, new SessionWindow(6, 7)), "4".getBytes());

		final KeyValueIterator<Windowed<Bytes>, byte[]> singleKeyIterator =
				cachingStore.findSessions(keyAA, 0L, 10L);
		final KeyValueIterator<Windowed<Bytes>, byte[]> keyRangeIterator =
				cachingStore.findSessions(keyAA, keyAA, 0L, 10L);

		assertEquals(singleKeyIterator.next(), keyRangeIterator.next());
		assertEquals(singleKeyIterator.next(), keyRangeIterator.next());
		assertFalse(singleKeyIterator.hasNext());
		assertFalse(keyRangeIterator.hasNext());
	}

	@Test
	public void shouldReturnSameResultsForSingleKeyFindSessionsBackwardsAndEqualKeyRangeFindSessions() {
		cachingStore.put(new Windowed<>(keyA, new SessionWindow(0, 1)), "1".getBytes());
		cachingStore.put(new Windowed<>(keyAA, new SessionWindow(2, 3)), "2".getBytes());
		cachingStore.put(new Windowed<>(keyAA, new SessionWindow(4, 5)), "3".getBytes());
		cachingStore.put(new Windowed<>(keyB, new SessionWindow(6, 7)), "4".getBytes());

		final KeyValueIterator<Windowed<Bytes>, byte[]> singleKeyIterator =
				cachingStore.backwardFindSessions(keyAA, 0L, 10L);
		final KeyValueIterator<Windowed<Bytes>, byte[]> keyRangeIterator =
				cachingStore.backwardFindSessions(keyAA, keyAA, 0L, 10L);

		assertEquals(singleKeyIterator.next(), keyRangeIterator.next());
		assertEquals(singleKeyIterator.next(), keyRangeIterator.next());
		assertFalse(singleKeyIterator.hasNext());
		assertFalse(keyRangeIterator.hasNext());
	}

	@Test
	public void shouldClearNamespaceCacheOnClose() {
		final Windowed<Bytes> a1 = new Windowed<>(keyA, new SessionWindow(0, 0));
		cachingStore.put(a1, "1".getBytes());
		assertEquals(1, cache.size());
		cachingStore.close();
		assertEquals(0, cache.size());
	}

	@Test
	public void shouldThrowIfTryingToFetchFromClosedCachingStore() {
		cachingStore.close();
		assertThrows(InvalidStateStoreException.class, () -> cachingStore.fetch(keyA));
	}

	@Test
	public void shouldThrowIfTryingToFindMergeSessionFromClosedCachingStore() {
		cachingStore.close();
		assertThrows(InvalidStateStoreException.class, () -> cachingStore.findSessions(keyA, 0, Long.MAX_VALUE));
	}

	@Test
	public void shouldThrowIfTryingToRemoveFromClosedCachingStore() {
		cachingStore.close();
		assertThrows(InvalidStateStoreException.class, () -> cachingStore.remove(new Windowed<>(keyA, new SessionWindow(0, 0))));
	}

	@Test
	public void shouldThrowIfTryingToPutIntoClosedCachingStore() {
		cachingStore.close();
		assertThrows(InvalidStateStoreException.class, () -> cachingStore.put(new Windowed<>(keyA, new SessionWindow(0, 0)), "1".getBytes()));
	}

	@Test
	public void shouldThrowNullPointerExceptionOnFindSessionsNullKey() {
		assertThrows(NullPointerException.class, () -> cachingStore.findSessions(null, 1L, 2L));
	}

	@Test
	public void shouldThrowNullPointerExceptionOnFindSessionsNullFromKey() {
		assertThrows(NullPointerException.class, () -> cachingStore.findSessions(null, keyA, 1L, 2L));
	}

	@Test
	public void shouldThrowNullPointerExceptionOnFindSessionsNullToKey() {
		assertThrows(NullPointerException.class, () -> cachingStore.findSessions(keyA, null, 1L, 2L));
	}

	@Test
	public void shouldThrowNullPointerExceptionOnFetchNullFromKey() {
		assertThrows(NullPointerException.class, () -> cachingStore.fetch(null, keyA));
	}

	@Test
	public void shouldThrowNullPointerExceptionOnFetchNullToKey() {
		assertThrows(NullPointerException.class, () -> cachingStore.fetch(keyA, null));
	}

	@Test
	public void shouldThrowNullPointerExceptionOnFetchNullKey() {
		assertThrows(NullPointerException.class, () -> cachingStore.fetch(null));
	}

	@Test
	public void shouldThrowNullPointerExceptionOnRemoveNullKey() {
		assertThrows(NullPointerException.class, () -> cachingStore.remove(null));
	}

	@Test
	public void shouldThrowNullPointerExceptionOnPutNullKey() {
		assertThrows(NullPointerException.class, () -> cachingStore.put(null, "1".getBytes()));
	}

	@Test
	public void shouldNotThrowInvalidRangeExceptionWhenBackwardWithNegativeFromKey() {
		final Bytes keyFrom = Bytes.wrap(Serdes.Integer().serializer().serialize("", -1));
		final Bytes keyTo = Bytes.wrap(Serdes.Integer().serializer().serialize("", 1));

		try (final LogCaptureAppender appender = LogCaptureAppender.createAndRegister(CachingSessionStore.class)) {
			final KeyValueIterator<Windowed<Bytes>, byte[]> iterator =
					cachingStore.backwardFindSessions(keyFrom, keyTo, 0L, 10L);
			assertFalse(iterator.hasNext());

			final List<String> messages = appender.getMessages();
			assertThat(
					messages,
					hasItem(
							"Returning empty iterator for fetch with invalid key range: from > to." +
									" This may be due to range arguments set in the wrong order, " +
									"or serdes that don't preserve ordering when lexicographically comparing the serialized bytes." +
									" Note that the built-in numerical serdes do not follow this for negative numbers"
					)
			);
		}
	}

	@Test
	public void shouldNotThrowInvalidRangeExceptionWithNegativeFromKey() {
		final Bytes keyFrom = Bytes.wrap(Serdes.Integer().serializer().serialize("", -1));
		final Bytes keyTo = Bytes.wrap(Serdes.Integer().serializer().serialize("", 1));

		try (final LogCaptureAppender appender = LogCaptureAppender.createAndRegister(CachingSessionStore.class)) {
			final KeyValueIterator<Windowed<Bytes>, byte[]> iterator = cachingStore.findSessions(keyFrom, keyTo, 0L, 10L);
			assertFalse(iterator.hasNext());

			final List<String> messages = appender.getMessages();
			assertThat(
					messages,
					hasItem(
							"Returning empty iterator for fetch with invalid key range: from > to." +
									" This may be due to range arguments set in the wrong order, " +
									"or serdes that don't preserve ordering when lexicographically comparing the serialized bytes." +
									" Note that the built-in numerical serdes do not follow this for negative numbers"
					)
			);
		}
	}

	private List<KeyValue<Windowed<Bytes>, byte[]>> addSessionsUntilOverflow(final String... sessionIds) {
		final Random random = new Random();
		final List<KeyValue<Windowed<Bytes>, byte[]>> results = new ArrayList<>();
		while (cache.size() == results.size()) {
			final String sessionId = sessionIds[random.nextInt(sessionIds.length)];
			addSingleSession(sessionId, results);
		}
		return results;
	}

	private void addSingleSession(final String sessionId, final List<KeyValue<Windowed<Bytes>, byte[]>> allSessions) {
		final int timestamp = allSessions.size() * 10;
		final Windowed<Bytes> key = new Windowed<>(Bytes.wrap(sessionId.getBytes()), new SessionWindow(timestamp, timestamp));
		final byte[] value = "1".getBytes();
		cachingStore.put(key, value);
		allSessions.add(KeyValue.pair(key, value));
	}

	public static class CacheFlushListenerStub<K, V> implements CacheFlushListener<byte[], byte[]> {
		private final Deserializer<K> keyDeserializer;
		private final Deserializer<V> valueDesializer;
		private final List<KeyValueTimestamp<K, Change<V>>> forwarded = new LinkedList<>();

		CacheFlushListenerStub(final Deserializer<K> keyDeserializer,
							   final Deserializer<V> valueDesializer) {
			this.keyDeserializer = keyDeserializer;
			this.valueDesializer = valueDesializer;
		}

		@Override
		public void apply(final byte[] key,
						  final byte[] newValue,
						  final byte[] oldValue,
						  final long timestamp) {
			forwarded.add(
					new KeyValueTimestamp<>(
							keyDeserializer.deserialize(null, key),
							new Change<>(
									valueDesializer.deserialize(null, newValue),
									valueDesializer.deserialize(null, oldValue)),
							timestamp
					)
			);
		}
	}
}
