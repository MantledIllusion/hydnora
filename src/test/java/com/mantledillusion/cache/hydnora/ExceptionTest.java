package com.mantledillusion.cache.hydnora;

import static org.junit.Assert.assertEquals;

import org.junit.Before;
import org.junit.Test;

import com.mantledillusion.cache.hydnora.exception.EntryLoadingException;
import com.mantledillusion.essentials.concurrency.locks.LockIdentifier;

public class ExceptionTest {

	private static final class StringLockIdentifier extends LockIdentifier {
		public StringLockIdentifier(String id) {
			super(id);
		}
	}
	
	private static final class FailingCache extends HydnoraCache<String, ExceptionTest.StringLockIdentifier> {
		
		@Override
		protected String load(StringLockIdentifier id) throws Exception {
			throw new RuntimeException("Error loading "+id);
		}
	}
	
	private FailingCache failingCache;
	
	@Before
	public void before() {
		this.failingCache = new FailingCache();
	}
	
	@Test(expected=IllegalArgumentException.class)
	public void testNullIdentifier() {
		this.failingCache.get(null);
	}
	
	@Test(expected=EntryLoadingException.class)
	public void testFailingLoad() {
		this.failingCache.get(new StringLockIdentifier("willFail"));
	}
	
	@Test(expected=RuntimeException.class)
	public void testUnwrappedFailingLoad() {
		this.failingCache.setWrapRuntimeExceptions(false);
		this.failingCache.get(new StringLockIdentifier("willFail"));
	}
	
	@Test
	public void testFallbackLoad() {
		String fallbackValue = "fallback";
		
		String loadedValue = this.failingCache.get(new StringLockIdentifier("willFail"), fallbackValue);
		assertEquals(fallbackValue, loadedValue);
	}
}
