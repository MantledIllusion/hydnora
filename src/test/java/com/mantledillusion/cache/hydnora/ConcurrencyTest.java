package com.mantledillusion.cache.hydnora;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.Before;
import org.junit.Test;

import com.mantledillusion.essentials.concurrency.locks.LockIdentifier;

public class ConcurrencyTest {
	
	private static final int LONG_PAUSED_INDEX_A = 0;
	private static final int LONG_PAUSED_INDEX_B = 1;
	private static final int SHORT_PAUSED_INDEX_A = 2;
	private static final int SHORT_PAUSED_INDEX_B = 3;
	private static final int FREE_INDEX = 4;
	
	private static final String LONG_PAUSED_ID = "longPausedId";
	private static final String FREE_ID = "freeId";
	private static final String SHORT_PAUSED_ID = "shortPausedId";

	private static final class IndexedLockIdentifier extends LockIdentifier {

		private final int index;
		private final String id;
		
		public IndexedLockIdentifier(int index, String id) {
			super(id);
			this.index = index;
			this.id = id;
		}
	}
	
	private final class PauseableCache extends HydnoraCache<IndexedLockIdentifier, String> {
		
		private Set<String> pausedIds = Collections.synchronizedSet(new HashSet<>());
		
		@Override
		protected String load(IndexedLockIdentifier id) throws Exception {
			while (this.pausedIds.contains(id.id)) {
				Thread.sleep(1);
			}
			ConcurrencyTest.this.loadingOrderIds.add(id.index);
			return String.valueOf(id.id);
		}
		
		private void pauseId(String id) {
			this.pausedIds.add(id);
		}
		
		private void resumeId(String id) {
			this.pausedIds.remove(id);
		}
	}
	
	private final class IdLoadingRunnable implements Runnable {

		private final int index;
		private final String id;
		
		public IdLoadingRunnable(int index, String id) {
			this.index = index;
			this.id = id;
		}

		@Override
		public void run() {
			ConcurrencyTest.this.cache.get(new IndexedLockIdentifier(this.index, this.id));
			ConcurrencyTest.this.retrievingOrderIds.add(this.index);
		}
	}
	
	private PauseableCache cache;
	private List<Integer> loadingOrderIds;
	private List<Integer> retrievingOrderIds;
	
	@Before
	public void before() {
		this.cache = new PauseableCache();
		this.loadingOrderIds = Collections.synchronizedList(new ArrayList<>());
		this.retrievingOrderIds = Collections.synchronizedList(new ArrayList<>());
	}
	
	@Test
	public void testConcurrency() throws InterruptedException, ExecutionException {
		
		ExecutorService executor = Executors.newFixedThreadPool(5);
		
		this.cache.pauseId(LONG_PAUSED_ID);
		this.cache.pauseId(SHORT_PAUSED_ID);
		
		Future<?> pausedFuture = executor.submit(new IdLoadingRunnable(LONG_PAUSED_INDEX_A, LONG_PAUSED_ID));
		Future<?> blockedFuture = executor.submit(new IdLoadingRunnable(LONG_PAUSED_INDEX_B, LONG_PAUSED_ID));
		Future<?> pausedFuture2 = executor.submit(new IdLoadingRunnable(SHORT_PAUSED_INDEX_A, SHORT_PAUSED_ID));
		Future<?> blockedFuture2 = executor.submit(new IdLoadingRunnable(SHORT_PAUSED_INDEX_B, SHORT_PAUSED_ID));
		Future<?> freeFuture = executor.submit(new IdLoadingRunnable(FREE_INDEX, FREE_ID));
		
		freeFuture.get();
		this.cache.resumeId(SHORT_PAUSED_ID);
		pausedFuture2.get();
		blockedFuture2.get();
		this.cache.resumeId(LONG_PAUSED_ID);
		pausedFuture.get();
		blockedFuture.get();
		
		// CHECK LOADED IDs
		assertEquals(3, this.loadingOrderIds.size());
		assertEquals(FREE_INDEX, this.loadingOrderIds.get(0).intValue());
		assertTrue(new HashSet<>(Arrays.asList(SHORT_PAUSED_INDEX_A, SHORT_PAUSED_INDEX_B)).contains(this.loadingOrderIds.get(1)));
		assertTrue(new HashSet<>(Arrays.asList(LONG_PAUSED_INDEX_A, LONG_PAUSED_INDEX_B)).contains(this.loadingOrderIds.get(2)));
		
		// CHECK RETRIEVED IDs
		assertEquals(5, this.retrievingOrderIds.size());
		assertEquals(FREE_INDEX, this.retrievingOrderIds.get(0).intValue());
		assertTrue(new HashSet<>(Arrays.asList(SHORT_PAUSED_INDEX_A, SHORT_PAUSED_INDEX_B)).containsAll(this.retrievingOrderIds.subList(1, 2)));
		assertTrue(new HashSet<>(Arrays.asList(LONG_PAUSED_INDEX_A, LONG_PAUSED_INDEX_B)).containsAll(this.retrievingOrderIds.subList(3, 4)));
	}
}
