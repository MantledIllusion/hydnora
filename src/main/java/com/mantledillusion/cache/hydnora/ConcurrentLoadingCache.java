package com.mantledillusion.cache.hydnora;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;

import com.mantledillusion.cache.hydnora.exception.EntryLoadingException;
import com.mantledillusion.essentials.concurrency.locks.LockIdentifier;

/**
 * Abstract cache base type that is able to hold huge amounts of loadable data
 * for completely different identifier types with each of them requesting for
 * completely different data types.
 * <p>
 * All operations on an instance of this cache are completely thread-safe within
 * that instance, while still being very high performance.
 * <p>
 * The cache synchronizes on the uniqueness of each identifier, so only
 * concurrent requests that touch the exact same data will block each other;
 * everything else will just run along.
 * <p>
 * Note that the order of retrieving from the cache by different identifiers
 * might not be the same as the order of requesting was.
 * <p>
 * This also applies for retrieving with equal identifiers; in fact, when
 * calling very shortly after each other, it even cannot be assured that the
 * first {@link Thread} calling will be the one performing the loading
 * operation. For example, 3 {@link Thread}s calling with equal identifiers in
 * the order [1,2,3] might be served by the cache in the order [2,1,3] where
 * each and every one of the {@link Thread}s might be the one that actually
 * performs the loading operation. Normally this is no problem at all, since
 * even in the most complicated case where the last {@link Thread} calling is
 * the one performing the loading operation (the others wait for it to complete)
 * and is then served last after completing, all of the {@link Thread}s are
 * served within milliseconds of each other.
 * <p>
 * All of the cache's {@link Method}s are {@code protected}, so the
 * implementation may decide whether to expose a cache {@link Method} or to
 * supply own {@link Method}s to supply data access.
 */
public abstract class ConcurrentLoadingCache<EntryType, EntryIdType extends LockIdentifier> {

	private final class Semaphore {
		Long timeStamp;

		void update() {
			timeStamp = System.currentTimeMillis();
		}

		boolean isExpired() {
			if (expiringInterval < 0) {
				return false;
			} else {
				return timeStamp == null || System.currentTimeMillis() - timeStamp >= expiringInterval;
			}
		}
	}

	private final Map<EntryIdType, Semaphore> semaphores = Collections.synchronizedMap(new HashMap<>());
	private final Map<EntryIdType, Object> cache = Collections.synchronizedMap(new HashMap<>());

	private boolean wrapRuntimeExceptions;
	private long expiringInterval;

	/**
	 * Default constructor, leaving all settings on default.
	 * <p>
	 * By default, entries never expire.
	 */
	protected ConcurrentLoadingCache() {
		this(-1);
	}

	/**
	 * Advanced constructor with settings.
	 * 
	 * @param expiringInterval
	 *            The interval in ms in which is needed for a loaded entry to
	 *            expire. Default is -1, so it never expires.
	 */
	protected ConcurrentLoadingCache(long expiringInterval) {
		this.expiringInterval = expiringInterval;
		this.wrapRuntimeExceptions = true;
	}

	/**
	 * Returns the expiring interval currently set.
	 * 
	 * @return The expiring interval; never null
	 */
	public long getExpiringInterval() {
		return expiringInterval;
	}

	/**
	 * Sets the interval in ms after that a loaded entry is expired. Negative values
	 * are allowed and will cause a reloading every time.
	 * 
	 * @param interval
	 *            The interval in ms after which the entries in the cache expire; 0
	 *            means expire directly, negative values mean no expiration.
	 */
	protected void setExpiringInterval(long interval) {
		this.expiringInterval = Math.max(interval, -1);
	}

	/**
	 * Returns whether {@link RuntimeException}s occurring during
	 * {@link #load(LockIdentifier)} are wrapped into
	 * {@link EntryLoadingException}s.
	 * 
	 * @return True if {@link RuntimeException}s should be wrapped, false otherwise;
	 *         true by default.
	 */
	public boolean isWrapRuntimeExceptions() {
		return wrapRuntimeExceptions;
	}

	/**
	 * Sets whether to wrap {@link RuntimeException}s occurring during
	 * {@link #load(LockIdentifier)} are wrapped into
	 * {@link EntryLoadingException}s.
	 * 
	 * @param wrapRuntimeExceptions
	 *            True if {@link RuntimeException}s should be wrapped, false
	 *            otherwise; true by default.
	 */
	public void setWrapRuntimeExceptions(boolean wrapRuntimeExceptions) {
		this.wrapRuntimeExceptions = wrapRuntimeExceptions;
	}

	/**
	 * Retrieves the entry for the given id from the cache, or loads it using the
	 * given id type's {@link CacheLoadingHandler} if necessary.
	 * <p>
	 * If loading fails, an {@link EntryLoadingException} is thrown.
	 * 
	 * @param id
	 *            The id that identifies the entry to retrieve; might <b>not</b> be
	 *            null.
	 * @return The entry that is identified by the given id; might be null if the
	 *         handler that has loaded the entry returned null
	 */
	protected final EntryType get(EntryIdType id) {
		return get(id, true, null);
	}

	/**
	 * Retrieves the entry for the given id from the cache, or loads it using the
	 * given id type's {@link CacheLoadingHandler} if necessary.
	 * <p>
	 * If loading fails, the given exception fallback entry instance is returned.
	 * 
	 * @param id
	 *            The id that identifies the entry to retrieve; might <b>not</b> be
	 *            null.
	 * @param exceptionFallback
	 *            The entry fallback instance to return if the entry to retrieve
	 *            from the cache has to be loaded and loading fails.
	 * @return The entry that is identified by the given id; might be null if the
	 *         handler that has loaded the entry returned null
	 */
	protected final EntryType get(EntryIdType id, EntryType exceptionFallback) {
		return get(id, false, exceptionFallback);
	}

	@SuppressWarnings("unchecked")
	private final EntryType get(EntryIdType id, boolean throwException, EntryType fallback) {
		if (id == null) {
			throw new IllegalArgumentException("Cannot retrieve an entry from the cache using a null id.");
		}

		boolean entryExisting = false;
		Semaphore semaphore;
		synchronized (this) {
			semaphore = this.semaphores.get(id);
			if (semaphore == null) {
				semaphore = new Semaphore();
				this.semaphores.put(id, semaphore);
			} else {
				entryExisting = true;
			}
		}

		synchronized (semaphore) {
			if (entryExisting && !semaphore.isExpired()) {
				return (EntryType) this.cache.get(id);
			}

			try {
				EntryType entry = load(id);
				synchronized (this) {
					this.semaphores.put(id, semaphore);
					this.cache.put(id, entry);
					semaphore.update();
				}
				return entry;
			} catch (Exception e) {
				if (throwException) {
					if (!this.wrapRuntimeExceptions && e instanceof RuntimeException) {
						throw (RuntimeException) e;
					}
					throw new EntryLoadingException(id, e);
				} else {
					return fallback;
				}
			}
		}
	}

	protected abstract EntryType load(EntryIdType id) throws Exception;

	/**
	 * Invalidates all expired entries in the cache.
	 * <p>
	 * References to invalidated entries are removed.
	 */
	protected final void clean() {
		synchronized (this) {
			Iterator<Entry<EntryIdType, Semaphore>> iter = this.semaphores.entrySet().iterator();
			while (iter.hasNext()) {
				Entry<EntryIdType, Semaphore> entry = iter.next();
				if (entry.getValue().isExpired()) {
					iter.remove();
					this.cache.remove(entry.getKey());
				}
			}
		}
	}

	/**
	 * Invalidates the whole cache.
	 * <p>
	 * References to all entries are removed.
	 */
	protected final void invalidate() {
		synchronized (this) {
			this.cache.clear();
			this.semaphores.clear();
		}
	}

	/**
	 * Invalidates the entry identified by the given id.
	 * <p>
	 * The reference to that entry is removed.
	 */
	protected final void invalidate(EntryIdType id) {
		synchronized (this) {
			this.cache.remove(id);
			this.semaphores.remove(id);
		}
	}

	/**
	 * Returns the size of the cache, which is the count of valid & expired entries.
	 * 
	 * @return The size of the cache; always >=0
	 */
	protected final long size() {
		synchronized (this) {
			return this.cache.size();
		}
	}

	/**
	 * Returns the size of the valid part of the cache, which is the count of valid
	 * entries.
	 * 
	 * @return The size of the valid part of the cache; always >=0
	 */
	protected final long validSize() {
		synchronized (this) {
			return this.semaphores.values().stream().filter(semaphore -> !semaphore.isExpired()).count();
		}
	}
}
