package com.mantledillusion.cache.hydnora;

import static org.junit.Assert.assertEquals;

import org.junit.Before;
import org.junit.Test;

import com.mantledillusion.cache.hydnora.exception.EntryLoadingException;

public class ExceptionTest {
	
	private static final class FailingCache extends HydnoraCache<String, String> {
		
		@Override
		protected String load(String id) throws Exception {
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
		this.failingCache.get("willFail");
	}
	
	@Test(expected=RuntimeException.class)
	public void testUnwrappedFailingLoad() {
		this.failingCache.setWrapRuntimeExceptions(false);
		this.failingCache.get("willFail");
	}
	
	@Test
	public void testFallbackLoad() {
		String fallbackValue = "fallback";
		
		String loadedValue = this.failingCache.get("willFail", fallbackValue);
		assertEquals(fallbackValue, loadedValue);
	}
}
