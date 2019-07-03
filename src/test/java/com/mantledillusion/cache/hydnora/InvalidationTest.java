package com.mantledillusion.cache.hydnora;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;

import org.junit.Before;
import org.junit.Test;

public class InvalidationTest {
	
	private static final int EXPIRING_INTERVAL = 100;
	
	private static final class ObjectCache extends HydnoraCache<String, Object> {
		
		@Override
		protected Object load(String id) throws Exception {
			return new Object();
		}
	}

	private static final String ID = "a";
	
	private ObjectCache cache;
	
	@Before
	public void before() {
		this.cache = new ObjectCache();
	}
	
	@Test
	public void testInvalidation() {
		Object o1 = this.cache.get(ID);
		Object o2 = this.cache.get(ID);

		assertSame(o1, o2);
		assertEquals(1, this.cache.size());
		assertEquals(0, this.cache.invalidatedSize());
		this.cache.invalidate();
		assertEquals(0, this.cache.size());
		assertEquals(0, this.cache.invalidatedSize());
		
		Object o3 = this.cache.get(ID);

		assertNotSame(o2, o3);
		assertEquals(1, this.cache.size());
		assertEquals(0, this.cache.invalidatedSize());
		this.cache.invalidate(ID);
		assertEquals(0, this.cache.size());
		assertEquals(0, this.cache.invalidatedSize());
		
		Object o4 = this.cache.get(ID);

		assertNotSame(o3, o4);
	}
	
	@Test
	public void testExpiring() throws InterruptedException {
		this.cache.setExpiringInterval(EXPIRING_INTERVAL);
		
		Object o1 = this.cache.get(ID);
		assertEquals(1, this.cache.size());
		assertEquals(0, this.cache.invalidatedSize());
		
		Thread.sleep(EXPIRING_INTERVAL);
		assertEquals(0, this.cache.size());
		assertEquals(1, this.cache.invalidatedSize());

		Object o2 = this.cache.get(ID);
		assertNotSame(o1, o2);
		assertEquals(1, this.cache.size());
		assertEquals(0, this.cache.invalidatedSize());

		Thread.sleep(EXPIRING_INTERVAL);
		assertEquals(0, this.cache.size());
		assertEquals(1, this.cache.invalidatedSize());

		this.cache.clean();
		assertEquals(0, this.cache.size());
		assertEquals(0, this.cache.invalidatedSize());
	}
}
