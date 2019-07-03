package com.mantledillusion.cache.hydnora;

import java.lang.ref.Reference;
import java.lang.ref.SoftReference;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.Predicate;

import com.mantledillusion.cache.hydnora.exception.EntryLoadingException;

/**
 * Abstract cache base type that is able to hold huge amounts of concurrently loadable entries.
 * <p>
 * The cache synchronizes on the uniqueness of each entries' identifier similar to a {@link HashMap}.
 * As a result, only concurrent operations that on the exact same entry will block each other; everything else will just
 * run along, allowing the cache to be both thread-safe and high performance. If an entry is identified by multiple
 * values, any POJO implementing {@link #equals(Object)} and {@link #hashCode()} or extensions of
 * {@link com.mantledillusion.essentials.concurrency.locks.LockIdentifier} can be used to create a collective identifier.
 * <p>
 * Note that the order of retrieving from the cache by different identifiers might not be the same as the order of
 * requesting was.
 * <p>
 * This also applies for retrieving entries with an equal identifier; in fact, when calling very shortly after each
 * other, it even cannot be assured that the first {@link Thread} requesting will be the one performing the loading
 * operation of the entry. For example, 3 {@link Thread}s requesting the same entry with equal id in the order [1,2,3]
 * might be served by the cache in the order [2,1,3], while each and every one of those {@link Thread}s might be the one
 * that actually performs the loading operation.
 * <p>
 * Normally this is no problem at all, since even in the most complicated case, where the last {@link Thread} calling is
 * the one performing the loading operation (the others wait for it to complete) and is then served last after completing,
 * all of the {@link Thread}s are served within milliseconds of each other, since the non-loading ones will simply
 * receive the same object.
 * <p>
 * The cache differentiates between entries that are valid and those that are not. When invalidating entries manually,
 * those entries are directly removed from the cache. But entries might also either expire when the they reside longer
 * in the cache than the cache's current expiry interval setting, or when they are referenced by the cache using any
 * other reference mode setting than {@link ReferenceMode#STRONG}, so garbage collection has already collected them. In
 * these cases, entries are invalidated automatically but <b>not</b> removed from the cache.
 * <p>
 * Such an entry (that is invalidated but still resides in the cache) is ignored by all entry based operations such as
 * {@link #contains(Object)} or {@link #size()}. Their presence can be observed using {@link #invalidatedSize()} and
 * they can be removed using {@link #clean()}.
 * <p>
 * All of the cache's {@link Method}s are {@code protected}, so the implementation may decide whether to expose a cache
 * {@link Method} or to supply own {@link Method}s for data access.
 */
public abstract class HydnoraCache<EntryIdType, EntryType> {

	private final class Semaphore {
		private Long timeStamp;

		void update() {
			timeStamp = System.currentTimeMillis();
		}

		private boolean isExpired() {
			if (expiringInterval < 0) {
				return false;
			} else {
				return timeStamp == null || System.currentTimeMillis() - timeStamp >= expiringInterval;
			}
		}
	}

	private interface CacheEntry<T> {

		default boolean exists() {
			return true;
		}

		T get();
	}

	private interface CacheEntryProvider {

		<T> CacheEntry<T> provide(T obj);
	}

	private static final class ReferencedCacheEntry<T> implements CacheEntry<T> {

		private final Reference<T> reference;

		private ReferencedCacheEntry(Reference<T> reference) {
			this.reference = reference;
		}

		@Override
		public boolean exists() {
			return this.reference == null || this.reference.get() != null;
		}

		@Override
		public T get() {
			return this.reference == null ? null : this.reference.get();
		}
	}

	/**
	 * Determines how entries of a {@link HydnoraCache} are referenced by the cache.
	 */
	public enum ReferenceMode {

		/**
		 * Default {@link Reference} setting; never let the garbage collector invalidate and collect any of the cache's
		 * entries.
		 */
		STRONG(new CacheEntryProvider() {

			@Override
			public <T> CacheEntry<T> provide(T obj) {
				return () -> obj;
			}
		}),

		/**
		 * GC {@link Reference} setting; reference cache entries using a {@link SoftReference}, causing entries to be
		 * invalidated and collected by a garbage collector when the memory is about to be full.
		 */
		SOFT(new CacheEntryProvider() {

			@Override
			public <T> CacheEntry<T> provide(T obj) {
				return new ReferencedCacheEntry<>(new SoftReference<>(obj));
			}
		}),

		/**
		 * GC {@link Reference} setting; reference cache entries using a {@link WeakReference}, causing entries to be
		 * invalidated and collected by a garbage collector as soon as the entry is not referenced by any other object
		 * than the cache itself anymore.
		 */
		WEAK(new CacheEntryProvider() {

			@Override
			public <T> CacheEntry<T> provide(T obj) {
				return new ReferencedCacheEntry<>(new WeakReference<>(obj));
			}
		});

		private final CacheEntryProvider provider;

		ReferenceMode(CacheEntryProvider provider) {
			this.provider = provider;
		}

		private <T> CacheEntry<T> toEntry(T obj) {
			return this.provider.provide(obj);
		}
	}

	private final Map<EntryIdType, Semaphore> semaphores = Collections.synchronizedMap(new HashMap<>());
	private final Map<EntryIdType, CacheEntry<EntryType>> cache = Collections.synchronizedMap(new HashMap<>());

	private ReferenceMode mode;
	private boolean wrapRuntimeExceptions;
	private long expiringInterval;

	/**
	 * Default constructor, leaving all settings on default.
	 * <p>
	 * By default, entries never expire.
	 */
	protected HydnoraCache() {
		this.expiringInterval = -1;
		this.wrapRuntimeExceptions = true;
		this.mode = ReferenceMode.STRONG;
	}

	/**
	 * Advanced constructor with settings.
	 * 
	 * @param expiringInterval
	 *            The interval in ms in which is needed for a loaded entry to
	 *            expire. Default is -1, so it never expires.
	 */
	protected HydnoraCache(long expiringInterval) {
		setExpiringInterval(expiringInterval);
		this.wrapRuntimeExceptions = true;
		this.mode = ReferenceMode.STRONG;
	}

	/**
	 * Advanced constructor with settings.
	 *
	 * @param mode The {@link ReferenceMode} to set, never null.
	 */
	protected HydnoraCache(ReferenceMode mode) {
		this.expiringInterval = -1;
		this.wrapRuntimeExceptions = true;
		setMode(mode);
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
	 * Sets the interval in ms after that a loaded entry is expired. Negative values are allowed and will cause a
	 * reloading every time.
	 * 
	 * @param interval
	 *            The interval in ms after which the entries in the cache expire; 0
	 *            means expire directly, negative values mean no expiration.
	 */
	protected void setExpiringInterval(long interval) {
		this.expiringInterval = Math.max(interval, -1);
	}

	/**
	 * Returns whether {@link RuntimeException}s occurring during {@link #load(Object)} are wrapped into
	 * {@link EntryLoadingException}s.
	 * 
	 * @return True if {@link RuntimeException}s should be wrapped, false otherwise;
	 *         true by default.
	 */
	public boolean isWrapRuntimeExceptions() {
		return wrapRuntimeExceptions;
	}

	/**
	 * Sets whether to wrap {@link RuntimeException}s occurring during {@link #load(Object)} are wrapped into
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
	 * Returns the current {@link ReferenceMode} of this cache, which is used when new entries are inserted.
	 *
	 * @return The current {@link ReferenceMode}, never null
	 */
	public ReferenceMode getMode() {
		return mode;
	}

	/**
	 * Sets the current {@link ReferenceMode} of this cache, which is used when new entries are inserted.
	 *
	 * @param mode The {@link ReferenceMode} to set, never null.
	 */
	public void setMode(ReferenceMode mode) {
		if (mode == null) {
			throw new IllegalArgumentException("Cannot set the cache to a null reference mode");
		}
		this.mode = mode;
	}

	/**
	 * Checks whether there is a valid entry in the cache for the given id.
	 *
	 * @param id The id that identifies the entry to check; might <b>not</b> be null.
	 * @return True if there is a valid entry, false otherwise
	 */
	protected boolean contains(EntryIdType id) {
		if (id == null) {
			throw new IllegalArgumentException("Cannot check an entry in the cache using a null id.");
		}
		synchronized (this) {
			return this.semaphores.containsKey(id) && !this.semaphores.get(id).isExpired() &&
					this.cache.containsKey(id) && this.cache.get(id).exists();
		}
	}

	/**
	 * Retrieves the entry for the given id from the cache, or loads it using the overridden {@link #load(Object)}
	 * {@link Method}.
	 * <p>
	 * If loading fails, an {@link EntryLoadingException} is thrown; or the original, if it is a
	 * {@link RuntimeException} and {@link #isWrapRuntimeExceptions()} is set to false.
	 * 
	 * @param id
	 *            The id that identifies the entry to retrieve; might <b>not</b> be
	 *            null.
	 * @return The entry that is identified by the given id; might be null if the {@link #load(Object)} {@link Method}
	 * returned null
	 */
	protected EntryType get(EntryIdType id) {
		return get(id, entry -> entry, true, null);
	}

	/**
	 * Retrieves the entry for the given id from the cache, or loads it using the overridden {@link #load(Object)}
	 * {@link Method}.
	 * <p>
	 * If loading fails, an {@link EntryLoadingException} is thrown; or the original, if it is a
	 * {@link RuntimeException} and {@link #isWrapRuntimeExceptions()} is set to false.
	 * 
	 * @param <ResultType>
	 *            The type required as result instead of the entries' type.
	 * @param id
	 *            The id that identifies the entry to retrieve; might <b>not</b> be null.
	 * @param mapper
	 *            A {@link Function} that is executed in the synchronization and able to map a found cache entry to the
	 *            required return type; might <b>not</b> be null.
	 * @return The entry that is identified by the given id; might be null if the {@link #load(Object)} {@link Method}
	 * returned null
	 */
	protected <ResultType> ResultType get(EntryIdType id, Function<EntryType, ResultType> mapper) {
		return get(id, mapper, true, null);
	}

	/**
	 * Retrieves the entry for the given id from the cache, or loads it using the overridden {@link #load(Object)}
	 * {@link Method}.
	 * <p>
	 * If loading fails, the given exception fallback entry instance is returned.
	 * 
	 * @param id
	 *            The id that identifies the entry to retrieve; might <b>not</b> be null.
	 * @param exceptionFallback
	 *            The entry fallback instance to return if the entry to retrieve from the cache has to be loaded and
	 *            loading fails.
	 * @return The entry that is identified by the given id; might be null if the {@link #load(Object)} {@link Method}
	 * returned null
	 */
	protected EntryType get(EntryIdType id, EntryType exceptionFallback) {
		return get(id, entry -> entry, false, exceptionFallback);
	}

	/**
	 * Retrieves the entry for the given id from the cache, or loads it using the overridden {@link #load(Object)}
	 * {@link Method}.
	 * <p>
	 * If loading fails, the given exception fallback entry instance is returned.
	 * 
	 * @param <ResultType>
	 *            The type required as result instead of the entries' type.
	 * @param id
	 *            The id that identifies the entry to retrieve; might <b>not</b> be null.
	 * @param mapper
	 *            A {@link Function} that is executed in the synchronization and able to map a found cache entry to the
	 *            required return type; might <b>not</b> be null.
	 * @param exceptionFallback
	 *            The entry fallback instance to return if the entry to retrieve from the cache has to be loaded and
	 *            loading fails.
	 * @return The entry that is identified by the given id; might be null if the {@link #load(Object)} {@link Method}
	 * returned null
	 */
	protected <ResultType> ResultType get(EntryIdType id, Function<EntryType, ResultType> mapper,
			EntryType exceptionFallback) {
		return get(id, mapper, false, exceptionFallback);
	}

	private <ResultType> ResultType get(EntryIdType id, Function<EntryType, ResultType> mapper,
			boolean throwException, EntryType fallback) {
		if (id == null) {
			throw new IllegalArgumentException("Cannot retrieve an entry from the cache using a null id.");
		} else if (mapper == null) {
			throw new IllegalArgumentException("Cannot retrieve an entry from the cache using a null mapper.");
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
			if (entryExisting && !semaphore.isExpired() && this.cache.containsKey(id) && this.cache.get(id).exists()) {
				return mapper.apply(this.cache.get(id).get());
			}

			try {
				EntryType entry = load(id);
				synchronized (this) {
					this.semaphores.put(id, semaphore);
					this.cache.put(id, this.mode.toEntry(entry));
					semaphore.update();
				}
				return mapper.apply(entry);
			} catch (Exception e) {
				if (throwException) {
					if (!this.wrapRuntimeExceptions && e instanceof RuntimeException) {
						throw (RuntimeException) e;
					}
					throw new EntryLoadingException(id, e);
				} else {
					return mapper.apply(fallback);
				}
			}
		}
	}

	/**
	 * Loads the entry for the given identifier.
	 * 
	 * @param id
	 *            The identifier to load the entry for; might <b>not</b> be null.
	 * @return The loaded entry; might be null
	 * @throws Exception
	 *             Any {@link Exception} that might be caused by the loading process; will be wrapped into an
	 *             {@link EntryLoadingException}, {@link RuntimeException}s will only be wrapped if
	 *             {@link #isWrapRuntimeExceptions()} is set to true.
	 */
	protected abstract EntryType load(EntryIdType id) throws Exception;

	/**
	 * Invalidates all expired entries in the cache.
	 * <p>
	 * References to invalidated entries are removed.
	 */
	protected void clean() {
		synchronized (this) {
			Iterator<Map.Entry<EntryIdType, Semaphore>> iter = this.semaphores.entrySet().iterator();
			while (iter.hasNext()) {
				Map.Entry<EntryIdType, Semaphore> entry = iter.next();
				if (entry.getValue().isExpired() || (this.cache.containsKey(entry.getKey()) &&
						!this.cache.get(entry.getKey()).exists())) {
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
	protected void invalidate() {
		synchronized (this) {
			this.cache.clear();
			this.semaphores.clear();
		}
	}

	/**
	 * Invalidates the whole cache.
	 * <p>
	 * References to matching entries are removed.
	 * 
	 * @param predicate
	 *            The predicate to check whether to invalidate the entry; might <b>not</b> be null.
	 */
	protected int invalidate(BiPredicate<EntryIdType, EntryType> predicate) {
		if (predicate == null) {
			throw new IllegalArgumentException("Cannot invalidate an entry from the cache using a null predicate.");
		}
		synchronized (this) {
			int invalidated = 0;
			Iterator<Map.Entry<EntryIdType, CacheEntry<EntryType>>> iter = this.cache.entrySet().iterator();
			while (iter.hasNext()) {
				Map.Entry<EntryIdType, CacheEntry<EntryType>> entry = iter.next();
				if (!entry.getValue().exists() || predicate.test(entry.getKey(), entry.getValue().get())) {
					iter.remove();
					this.cache.remove(entry.getKey());
					invalidated++;
				}
			}
			return invalidated;
		}
	}

	/**
	 * Invalidates the entry identified by the given id.
	 * <p>
	 * The reference to that entry is removed.
	 * 
	 * @param id
	 *            The identifier to load the entry for; might <b>not</b> be null.
	 */
	protected void invalidate(EntryIdType id) {
		synchronized (this) {
			this.cache.remove(id);
			this.semaphores.remove(id);
		}
	}

	/**
	 * Invalidates the entry identified by the given id.
	 * <p>
	 * The reference to matching entry is removed.
	 * 
	 * @param id
	 *            The identifier to load the entry for; might <b>not</b> be null.
	 * @param predicate
	 *            The predicate to check whether to invalidate the entry; might <b>not</b> be null.
	 */
	protected boolean invalidate(EntryIdType id, Predicate<EntryType> predicate) {
		if (predicate == null) {
			throw new IllegalArgumentException("Cannot invalidate an entry from the cache using a null predicate.");
		}
		synchronized (this) {
			if (this.cache.containsKey(id)) {
				if (!this.cache.get(id).exists() || predicate.test(this.cache.get(id).get())) {
					this.cache.remove(id);
					this.semaphores.remove(id);
					return true;
				}
			}
			return false;
		}
	}

	/**
	 * Returns the size of the valid part of the cache, which is the count of valid entries.
	 * 
	 * @return The size of the cache; always &gt;=0
	 */
	protected long size() {
		synchronized (this) {
			Predicate<Map.Entry<EntryIdType, Semaphore>> notExpired = entry -> !entry.getValue().isExpired();
			Predicate<Map.Entry<EntryIdType, Semaphore>> existing = entry -> this.cache.containsKey(entry.getKey()) &&
					this.cache.get(entry.getKey()).exists();

			return this.semaphores.entrySet().parallelStream().filter(notExpired.and(existing)).count();
		}
	}

	/**
	 * Returns the size of the invalidated part of the cache, which is the count of invalidated entries.
	 * 
	 * @return The size of the valid part of the cache; always &gt;=0
	 */
	protected long invalidatedSize() {
		synchronized (this) {
			Predicate<Map.Entry<EntryIdType, Semaphore>> expired = entry -> entry.getValue().isExpired();
			Predicate<Map.Entry<EntryIdType, Semaphore>> notExisting = entry -> !this.cache.containsKey(entry.getKey()) ||
					!this.cache.get(entry.getKey()).exists();

			return this.semaphores.entrySet().parallelStream().filter(expired.or(notExisting)).count();
		}
	}
}
