package com.mantledillusion.cache.hydnora;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;

import org.junit.Before;
import org.junit.Test;

import com.mantledillusion.essentials.concurrency.locks.LockIdentifier;

public class InvalidationTest {
	
	private static final int EXPIRING_INTERVAL = 100;

	private static final class AlwaysEqualLockIdentifier extends LockIdentifier {
		
		public AlwaysEqualLockIdentifier() {
			super("id");
		}
	}
	
	private static final class ObjectCache extends HydnoraCache<Object, AlwaysEqualLockIdentifier> {
		
		@Override
		protected Object load(AlwaysEqualLockIdentifier id) throws Exception {
			return new Object();
		}
	}
	
	private ObjectCache cache;
	
	@Before
	public void before() {
		this.cache = new ObjectCache();
	}
	
	@Test
	public void testInvalidation() {
		AlwaysEqualLockIdentifier id = new AlwaysEqualLockIdentifier();
		
		Object o1 = this.cache.get(id);
		Object o2 = this.cache.get(id);

		assertEquals(1, this.cache.size());
		assertEquals(1, this.cache.validSize());
		this.cache.invalidate();
		assertEquals(0, this.cache.size());
		assertEquals(0, this.cache.validSize());
		
		Object o3 = this.cache.get(id);

		assertEquals(1, this.cache.size());
		assertEquals(1, this.cache.validSize());
		this.cache.invalidate(id);
		assertEquals(0, this.cache.size());
		assertEquals(0, this.cache.validSize());
		
		Object o4 = this.cache.get(id);
		
		assertSame(o1, o2);
		assertNotSame(o2, o3);
		assertNotSame(o3, o4);
	}
	
	@Test
	public void testExpiring() throws InterruptedException {
		this.cache.setExpiringInterval(EXPIRING_INTERVAL);
		
		AlwaysEqualLockIdentifier id = new AlwaysEqualLockIdentifier();
		
		Object o1 = this.cache.get(id);
		assertEquals(1, this.cache.size());
		assertEquals(1, this.cache.validSize());
		
		Thread.sleep(EXPIRING_INTERVAL);
		assertEquals(1, this.cache.size());
		assertEquals(0, this.cache.validSize());

		Object o2 = this.cache.get(id);
		assertEquals(1, this.cache.size());
		assertEquals(1, this.cache.validSize());

		assertNotSame(o1, o2);

		Thread.sleep(EXPIRING_INTERVAL);
		this.cache.clean();
		assertEquals(0, this.cache.size());
		assertEquals(0, this.cache.validSize());
	}
}
