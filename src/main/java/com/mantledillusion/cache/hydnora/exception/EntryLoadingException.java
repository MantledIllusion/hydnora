package com.mantledillusion.cache.hydnora.exception;

import com.mantledillusion.cache.hydnora.HydnoraCache;
import com.mantledillusion.essentials.concurrency.locks.LockIdentifier;

/**
 * {@link RuntimeException} that might by thrown by an
 * {@link HydnoraCache} if any non-{@link RuntimeException} is thrown
 * during loading an entry and no fallback was specified for that case.
 */
public final class EntryLoadingException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	public EntryLoadingException(LockIdentifier id, Exception cause) {
		super("Unable to load entry for entry id with the content '" + id + "': " + cause.getMessage(), cause);
	}
}